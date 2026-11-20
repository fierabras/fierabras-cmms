#!/bin/bash
# Atlas CMMS Backup and Restore Utility
# Linux version for Docker environments
# This script allows users to backup and restore the Atlas CMMS system

# Default variables - Will be overridden by .env file if it exists
POSTGRES_USER="rootUser"
POSTGRES_PWD="mypassword"
MINIO_USER="minio"
MINIO_PASSWORD="minio123"

# Directory where backups will be stored
BACKUP_DIR="./atlas_backups"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

# Function to display usage information
show_usage() {
    echo "Usage: ./atlas-backup.sh [backup|restore] [options]"
    echo ""
    echo "Commands:"
    echo "  backup                Create a backup of Atlas CMMS"
    echo "  restore <filename>    Restore Atlas CMMS from a backup file"
    echo ""
    echo "Options:"
    echo "  --skip-db             Skip database backup/restore"
    echo "  --skip-files          Skip MinIO files backup/restore"
    echo "  --help                Display this help message"
    exit 1
}

# Function to check if Docker is running
check_docker_running() {
    if ! docker ps >/dev/null 2>&1; then
        echo "Error: Cannot connect to Docker. Make sure Docker is running."
        return 1
    fi
    return 0
}

# Function to check if container exists and is running
check_container_running() {
    container_name="$1"
    if [ -z "$(docker ps --filter "name=$container_name" --format "{{.Names}}" 2>/dev/null)" ]; then
        return 1
    fi
    return 0
}

# Function to load environment variables from .env file
load_env_file() {
    if [ -f .env ]; then
        while IFS= read -r line || [ -n "$line" ]; do
            # Skip comments and empty lines
            [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue

            # Extract key and value
            if [[ "$line" =~ ^([^=]+)=(.*)$ ]]; then
                key="${BASH_REMATCH[1]}"
                value="${BASH_REMATCH[2]}"

                # Remove quotes if present
                value="${value%\"}"
                value="${value#\"}"
                value="${value%\'}"
                value="${value#\'}"

                # Set variables if they match our expected ones
                case "$key" in
                    "POSTGRES_USER") POSTGRES_USER="$value" ;;
                    "POSTGRES_PWD") POSTGRES_PWD="$value" ;;
                    "MINIO_USER") MINIO_USER="$value" ;;
                    "MINIO_PASSWORD") MINIO_PASSWORD="$value" ;;
                esac
            fi
        done < .env
    fi
}

# Function to backup the system
backup_atlas_cmms() {
    BACKUP_FILENAME="atlas_backup_${TIMESTAMP}.tar.gz"
    TEMP_DIR="/tmp/atlas_backup_$TIMESTAMP"

    echo "Starting Atlas CMMS backup..."

    # Check if Docker is running
    if ! check_docker_running; then
        exit 1
    fi

    # Create temp directory
    mkdir -p "$TEMP_DIR"

    # Backup Postgres database
    if [ "$SKIP_DB" != "true" ]; then
        echo "Backing up PostgreSQL database..."

        if ! check_container_running "atlas_db"; then
            echo "Error: Atlas database container (atlas_db) is not running."
            echo "Make sure your Atlas CMMS environment is up and running."
            rm -rf "$TEMP_DIR"
            exit 1
        fi

        docker exec atlas_db pg_dump -U "$POSTGRES_USER" --encoding=UTF8 atlas > "$TEMP_DIR/atlas_db.sql"

        if [ $? -eq 0 ]; then
            echo "Database backup complete."
        else
            echo "Error: Database backup failed."
            rm -rf "$TEMP_DIR"
            exit 1
        fi
    fi

    # Backup MinIO data
    if [ "$SKIP_FILES" != "true" ]; then
        echo "Backing up MinIO data..."

        if ! check_container_running "atlas_minio"; then
            echo "Error: MinIO container (atlas_minio) is not running."
            echo "Make sure your Atlas CMMS environment is up and running."
            rm -rf "$TEMP_DIR"
            exit 1
        fi

        mkdir -p "$TEMP_DIR/minio_data"

        # Create a script to run inside a temporary container
        cat > "$TEMP_DIR/minio_backup.sh" << EOL
#!/bin/sh
set -e
mc alias set atlas-minio http://minio:9000 "$MINIO_USER" "$MINIO_PASSWORD" --api S3v4
mc mirror atlas-minio/atlas-bucket /backup_data
echo "MinIO backup complete."
EOL

        chmod +x "$TEMP_DIR/minio_backup.sh"

        # Run a temporary container to backup MinIO data
        echo "Running MinIO backup..."

        docker run --rm \
            --network atlas-cmms_default \
            -v "$TEMP_DIR/minio_backup.sh:/minio_backup.sh" \
            -v "$TEMP_DIR/minio_data:/backup_data" \
            --entrypoint /bin/sh minio/minio:RELEASE.2025-04-22T22-12-26Z /minio_backup.sh

        if [ $? -ne 0 ]; then
            echo "Error: MinIO backup failed."
            rm -rf "$TEMP_DIR"
            exit 1
        fi
    fi

    # Create backup directory if it doesn't exist
    mkdir -p "$BACKUP_DIR"

    # Create backup archive
    echo "Creating backup archive..."
    tar -czf "$BACKUP_DIR/$BACKUP_FILENAME" -C "$TEMP_DIR" .

    # Clean up
    rm -rf "$TEMP_DIR"

    echo "Backup completed successfully: $BACKUP_DIR/$BACKUP_FILENAME"
}

# Function to restore the system
restore_atlas_cmms() {
    BACKUP_FILE="$1"

    # Check if backup file is specified
    if [ -z "$BACKUP_FILE" ]; then
        echo "Error: Backup filename not specified."
        show_usage
    fi

    # Check if backup file exists
    if [ ! -f "$BACKUP_FILE" ]; then
        echo "Error: Backup file not found: $BACKUP_FILE"
        exit 1
    fi

    # Create temp directory for extraction
    TEMP_DIR="/tmp/atlas_restore_$TIMESTAMP"

    echo "Starting Atlas CMMS restore..."

    # Check if Docker is running
    if ! check_docker_running; then
        exit 1
    fi

    # Extract the backup
    echo "Extracting backup archive..."
    mkdir -p "$TEMP_DIR"
    if ! tar -xzf "$BACKUP_FILE" -C "$TEMP_DIR"; then
        echo "Error: Could not extract the backup archive. Nothing was restored."
        rm -rf "$TEMP_DIR"
        exit 1
    fi

    # Restore Postgres database
    if [ "$SKIP_DB" != "true" ] && [ -f "$TEMP_DIR/atlas_db.sql" ]; then
        echo "Restoring PostgreSQL database..."

        if ! check_container_running "atlas_db"; then
            echo "Error: Atlas database container (atlas_db) is not running."
            echo "Make sure your Atlas CMMS environment is up and running."
            rm -rf "$TEMP_DIR"
            exit 1
        fi

        echo "Warning: This will overwrite your current database."
        read -p "Continue with database restore? (y/n): " confirm
        if [[ "$confirm" =~ ^[yY] ]]; then
            # Drop old backup DB if it exists
            docker exec atlas_db bash -c "PGPASSWORD=$POSTGRES_PWD psql -U $POSTGRES_USER -d postgres -c 'DROP DATABASE IF EXISTS atlas_old;'"

            # Rename current "atlas" to "atlas_old"
            if ! docker exec atlas_db bash -c "PGPASSWORD=$POSTGRES_PWD psql -U $POSTGRES_USER -d postgres -c 'ALTER DATABASE atlas RENAME TO atlas_old;'"; then
                echo "Error: Could not rename the current database. Make sure the backend container is stopped. Nothing was restored."
                rm -rf "$TEMP_DIR"
                exit 1
            fi

            # Create new "atlas" database and restore from backup in a single
            # transaction, stopping at the first error
            if docker exec atlas_db bash -c "PGPASSWORD=$POSTGRES_PWD psql -U $POSTGRES_USER -d postgres -c 'CREATE DATABASE atlas;'" &&
                docker exec -i atlas_db psql -U "$POSTGRES_USER" -d atlas --set client_encoding=UTF8 -v ON_ERROR_STOP=1 --single-transaction < "$TEMP_DIR/atlas_db.sql"; then
                echo "Database restore complete."
            else
                echo "Error: Database restore failed. Rolling back to the previous database..."
                docker exec atlas_db bash -c "PGPASSWORD=$POSTGRES_PWD psql -U $POSTGRES_USER -d postgres -c 'DROP DATABASE IF EXISTS atlas;'"
                if docker exec atlas_db bash -c "PGPASSWORD=$POSTGRES_PWD psql -U $POSTGRES_USER -d postgres -c 'ALTER DATABASE atlas_old RENAME TO atlas;'"; then
                    echo "The previous database was put back. Files were not restored."
                else
                    echo "Error: Rollback failed. The previous database is in atlas_old."
                fi
                rm -rf "$TEMP_DIR"
                exit 1
            fi
        else
            echo "Database restore skipped."
        fi
    fi

    # Restore MinIO data
    if [ "$SKIP_FILES" != "true" ] && [ -d "$TEMP_DIR/minio_data" ]; then
        echo "Restoring MinIO data..."

        if ! check_container_running "atlas_minio"; then
            echo "Error: MinIO container (atlas_minio) is not running."
            echo "Make sure your Atlas CMMS environment is up and running."
            rm -rf "$TEMP_DIR"
            exit 1
        fi

        echo "Warning: This will overwrite files in the MinIO bucket."
        read -p "Continue with MinIO restore? (y/n): " confirm
        if [[ "$confirm" =~ ^[yY] ]]; then
            # Create a script to run inside a temporary container
            cat > "$TEMP_DIR/minio_restore.sh" << EOL
#!/bin/sh
set -e
mc alias set atlas-minio http://minio:9000 "$MINIO_USER" "$MINIO_PASSWORD" --api S3v4
mc mirror /backup_data atlas-minio/atlas-bucket --overwrite
echo "MinIO restore complete."
EOL

            chmod +x "$TEMP_DIR/minio_restore.sh"

            # Run a temporary container to restore MinIO data
            echo "Running MinIO restore..."

            docker run --rm \
                --network atlas-cmms_default \
                -v "$TEMP_DIR/minio_restore.sh:/minio_restore.sh" \
                -v "$TEMP_DIR/minio_data:/backup_data" \
                --entrypoint /bin/sh minio/minio:RELEASE.2025-04-22T22-12-26Z /minio_restore.sh

            if [ $? -ne 0 ]; then
                echo "Error: MinIO restore failed."
                exit 1
            else
                echo "MinIO restore complete."
            fi
        else
            echo "MinIO restore skipped."
        fi
    fi

    # Clean up
    rm -rf "$TEMP_DIR"

    echo "Restore completed successfully."
}

# Main script execution

# Load environment variables
load_env_file

# Initialize flags
SKIP_DB=false
SKIP_FILES=false

# Process command line arguments
COMMAND="$1"
shift

while [ "$#" -gt 0 ]; do
    case "$1" in
        --skip-db)
            SKIP_DB=true
            ;;
        --skip-files)
            SKIP_FILES=true
            ;;
        --help)
            show_usage
            ;;
        *)
            BACKUP_FILE="$1"
            ;;
    esac
    shift
done

# Execute the appropriate command
case "$COMMAND" in
    backup)
        backup_atlas_cmms
        ;;
    restore)
        restore_atlas_cmms "$BACKUP_FILE"
        ;;
    help)
        show_usage
        ;;
    *)
        echo "Error: Invalid command."
        show_usage
        ;;
esac