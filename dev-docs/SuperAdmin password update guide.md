# 🔑 Super admin Configuration and Password Update Guide

## 1️⃣ Configure the Super Admin

The platform super admin is configured through environment variables (`.env` / `docker-compose.yml`, service `api`).
It is **disabled by default**, and there are no default credentials.

```env
SUPER_ADMIN_ENABLED=false
SUPER_ADMIN_EMAIL=
SUPER_ADMIN_PASSWORD=
```

| Variable | Meaning |
|---|---|
| `SUPER_ADMIN_ENABLED` | `true` to create or enable the super admin, `false` (default) to keep it disabled |
| `SUPER_ADMIN_EMAIL` | Email of the super admin. Only used when it is created |
| `SUPER_ADMIN_PASSWORD` | Initial password of the super admin. Only used when it is created |

### Behavior on each startup

| Situation | Result |
|---|---|
| `SUPER_ADMIN_ENABLED=false` and no super admin exists | It is not created |
| `SUPER_ADMIN_ENABLED=false` and a super admin exists | It is **disabled** (it can no longer sign in) |
| `SUPER_ADMIN_ENABLED=true`, no super admin exists, email and password set | It is created with that email and password |
| `SUPER_ADMIN_ENABLED=true`, no super admin exists, email or password missing | It is not created; a warning is logged and the startup continues |
| `SUPER_ADMIN_ENABLED=true` and a super admin exists | It is enabled; **its password is not changed** |

Notes:
- `SUPER_ADMIN_EMAIL` and `SUPER_ADMIN_PASSWORD` are **only used to create** the super admin. The password is not
  synchronized on later startups: changing `SUPER_ADMIN_PASSWORD` does not change the password of an existing super
  admin. Use the password change below instead.
- Changing `SUPER_ADMIN_EMAIL` when the super admin already exists does not change its email; only a warning is logged.
- The password is never written to the logs. After the first startup you can remove `SUPER_ADMIN_PASSWORD` from the
  environment.
- When upgrading an installation that already has a super admin, set `SUPER_ADMIN_ENABLED=true` before the upgrade if
  you still need it; otherwise it is disabled on the next startup.

---

## 2️⃣ Sign In

Send a `POST` request to your authentication endpoint with the super admin credentials you configured:

**Endpoint:** `/auth/signin`  
**Method:** `POST`  
**Headers:**
```json
{
  "Content-Type": "application/json"
}
```
**Body:**
```json
{
  "email": "<SUPER_ADMIN_EMAIL>",
  "password": "<current super admin password>",
  "type": "SUPER_ADMIN"
}
```

📌 **Response (Example)**:
```json
{
  "accessToken": "<JWT access token>"
}
```
Save this `accessToken`, as you'll need it for authentication.

---

## 3️⃣ Change Password

To update your password, send a `POST` request to the `/updatepwd` endpoint.

**Endpoint:** `/auth/updatepwd`  
**Method:** `POST`  
**Headers:**
```json
{
  "Content-Type": "application/json",
  "Authorization": "Bearer YOUR_ACCESS_TOKEN"
}
```
**Body:**
```json
{
  "oldPassword": "<current super admin password>",
  "newPassword": "YourNewSecurePassword"
}
```

📌 **Response (Success)**:
```json
{
  "success": true,
  "message": "Password changed successfully"
}
```
