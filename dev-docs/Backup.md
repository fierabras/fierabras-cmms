# Atlas CMMS Backup and Restore Guide

This document provides instructions for backing up and restoring your Atlas CMMS system using the provided utility script. The utility is a Bash script for Linux. On Windows, run the same script from a WSL 2 Linux distribution (for example Ubuntu) with Docker Desktop WSL integration enabled, following the Linux instructions.

## Prerequisites

- Docker and Docker Compose installed and running
- Atlas CMMS system up and running
- Administrative access to your system

## Linux Instructions

### Setup

1. Save the [atlas-backup.sh](../scripts/backup/atlas-backup.sh) script to your Atlas CMMS project directory
2. Open a terminal
3. Navigate to your Atlas CMMS project directory:
   ```bash
   cd /path/to/your/atlas/project
   ```
4. Make the script executable:
   ```bash
   chmod +x atlas-backup.sh
   ```

### Creating a Backup

To create a complete backup of your Atlas CMMS system:

```bash
./atlas-backup.sh backup
```

The script will:
1. Check if Docker is running
2. Back up the PostgresSQL database
3. Back up the MinIO file storage
4. Create a tar.gz archive in the `atlas_backups` directory

#### Optional Parameters

To skip backing up the database:
```bash
./atlas-backup.sh backup --skip-db
```

To skip backing up the files:
```bash
./atlas-backup.sh backup --skip-files
```

### Restoring from a Backup
Ensure the backend container is stopped.
To restore your Atlas CMMS system from a backup:

```bash
./atlas-backup.sh restore ./atlas_backups/atlas_backup_YYYYMMDD_HHMMSS.tar.gz
```

Replace `YYYYMMDD_HHMMSS` with the timestamp of your backup file.

#### Optional Parameters

To skip restoring the database:
```bash
./atlas-backup.sh restore ./atlas_backups/atlas_backup_YYYYMMDD_HHMMSS.tar.gz --skip-db
```

To skip restoring the files:
```bash
./atlas-backup.sh restore ./atlas_backups/atlas_backup_YYYYMMDD_HHMMSS.tar.gz --skip-files
```

## Environment Configuration

The script automatically reads credentials from a `.env` file in the same directory. If your Atlas CMMS uses custom credentials, make sure your `.env` file contains the following variables:

```
POSTGRES_USER=your_postgres_username
POSTGRES_PWD=your_postgres_password
MINIO_USER=your_minio_username
MINIO_PASSWORD=your_minio_password
```

If the `.env` file is not present, the script will use default credentials:

## Important Notes

1. **Database Restoration**: When restoring a database, the script renames your current database to `atlas_old` before creating a new `atlas` database with the backup data. The data is loaded in a single transaction that stops at the first error. If the archive cannot be extracted, nothing is restored; if loading fails, the script puts `atlas_old` back as `atlas`, skips the file restore and exits with a non-zero code. Any `atlas_old` left by a previous restore is dropped when a new restore starts.

2. **Confirmation Prompts**: The restore operation will prompt for confirmation before overwriting your database or files.

3. **Backup Directory**: All backups are stored in an `atlas_backups` directory created in the same location as the script.

4. **Network Configuration**: The script assumes your Docker Compose network is named `atlas-cmms_default`. If your setup uses a different network name, you'll need to modify the script accordingly.

5. **MinIO Client**: The script runs the MinIO Client (`mc`) bundled in the `minio/minio` image pinned in `docker-compose.yml`; nothing is downloaded from the Internet at run time. If you change the MinIO image tag in `docker-compose.yml`, update it in the script too.

## Troubleshooting

### Docker Not Running

If you receive an error about Docker not running, ensure Docker Desktop (Windows) or Docker service (Linux) is started:

- Windows: Open Docker Desktop application
- Linux: Run `sudo systemctl start docker`

### Container Not Found

If you see errors about containers not being found, ensure your Atlas CMMS environment is running:

```bash
docker-compose ps  # Check if containers are running
docker-compose up -d  # Start containers if needed
```

### Permission Denied (Linux)

If you encounter permission errors on Linux, make sure the script is executable:

```bash
chmod +x atlas-backup.sh
```

### Network Issues

If the script cannot connect to containers, verify your Docker network configuration:

```bash
docker network ls
```

If your Atlas CMMS network is not named `atlas-cmms_default`, you'll need to update the network name in the script.