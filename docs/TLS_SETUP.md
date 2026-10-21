# TLS/HTTPS Setup for Dashboard

The dashboard can serve HTTPS itself. Set a PKCS12 keystore and its password in `.env` and the HTTP port speaks TLS (session cookies are marked Secure and HSTS is sent):

```bash
./scripts/generate-selfsigned-cert.sh localhost dashboard.p12 changeit   # a self-signed one for testing
# in .env (inside Docker, mount the file and use its path in the container)
TLS_KEYSTORE=/app/certs/dashboard.p12
TLS_KEYSTORE_PASSWORD=changeit
```

If `TLS_KEYSTORE` is set but unreadable, the password is wrong or `TLS_KEYSTORE_PASSWORD` is missing, the engine refuses to start rather than falling back to plain HTTP. A self-signed certificate makes browsers warn; use a real certificate for anything exposed. The React dashboard uses the HTTP API only, so nothing else needs TLS. The raw WebSocket feed (port + 1) stays plain `ws://`: keep it bound to localhost or behind a proxy. This is covered by `DashboardTlsTest`, which generates a keystore and talks HTTPS to a running server.

The alternative below, a reverse proxy that terminates TLS, still works and is the better choice when one already exists.

## Quick Start (Development)

### Option 1: Self-Signed Certificate with Reverse Proxy

1. **Generate a self-signed certificate:**
```bash
./scripts/generate-selfsigned-cert.sh localhost dashboard.p12 changeit
```

2. **Use a reverse proxy (nginx example):**

```nginx
server {
    listen 443 ssl;
    server_name localhost;

    ssl_certificate     /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

3. **Configure the engine to trust the proxy:**
```bash
# In .env
TRUSTED_PROXIES=127.0.0.1
COOKIE_SECURE=true
```

### Option 2: SSH Tunnel (No Certificate Required)

For local development without certificate management:

```bash
ssh -L 8443:127.0.0.1:8080 -L 8444:127.0.0.1:8081 user@remote-server
```

Then access via `https://localhost:8443` (using SSH's built-in encryption).

## Production Setup

### Using nginx

1. **Obtain a certificate from Let's Encrypt:**
```bash
sudo certbot certonly --standalone -d your-domain.com
```

2. **Configure nginx:**
```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate     /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers         HIGH:!aNULL:!MD5;

    # Security headers
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /ws {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

3. **Configure the engine:**
```bash
# In .env
TRUSTED_PROXIES=127.0.0.1,your-proxy-ip
COOKIE_SECURE=true
BIND_ADDRESS=127.0.0.1
```

### Using Caddy (Simpler Alternative)

Caddy automatically handles HTTPS with Let's Encrypt:

```caddyfile
your-domain.com {
    reverse_proxy 127.0.0.1:8080
    reverse_proxy /ws 127.0.0.1:8081
}
```

## Security Considerations

### When to Use TLS

- **Always** in production
- **Recommended** for development on shared networks
- **Optional** for isolated local development (localhost only)

### Security Headers

When using TLS, enable these headers in your reverse proxy:

- `Strict-Transport-Security` (HSTS): Forces HTTPS
- `X-Frame-Options`: Prevents clickjacking
- `X-Content-Type-Options`: Prevents MIME sniffing
- `Content-Security-Policy`: Restricts resource loading

### Cookie Security

Set `COOKIE_SECURE=true` in `.env` when behind TLS to:
- Mark session cookies as `Secure`
- Send HSTS header

### Trusted Proxies

Set `TRUSTED_PROXIES` to the IP addresses of your reverse proxies:
- Single proxy: `TRUSTED_PROXIES=127.0.0.1`
- Multiple proxies: `TRUSTED_PROXIES=127.0.0.1,10.0.0.5,10.0.0.6`

## Docker Setup

### Using nginx as a sidecar container

```yaml
# docker-compose.yml
version: '3.8'
services:
  engine:
    build: .
    ports:
      - "127.0.0.1:8080:8080"
      - "127.0.0.1:8081:8081"
    environment:
      - TRUSTED_PROXIES=172.16.0.0/12
      - COOKIE_SECURE=true
    volumes:
      - engine-data:/app/data

  nginx:
    image: nginx:alpine
    ports:
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./certs:/etc/nginx/certs:ro
    depends_on:
      - engine

volumes:
  engine-data:
```

## Troubleshooting

### Certificate Warnings in Browser

For self-signed certificates, browsers will show security warnings. To resolve:

1. **Temporary (development):** Accept the warning and proceed
2. **Permanent:** Import the certificate into your browser's trust store

### WebSocket Connection Failures

Ensure your reverse proxy properly handles WebSocket upgrades:
- Must pass `Upgrade` and `Connection` headers
- Must use HTTP/1.1 or HTTP/2

### Mixed Content Errors

If you see mixed content errors:
- Ensure all resources use HTTPS
- Check that API calls use `https://` not `http://`
- Verify `X-Forwarded-Proto` header is set correctly

## Alternative: JDK HttpServer with HTTPS (Not Recommended)

JDK HttpServer can use HTTPS with `HttpsServer`, but this requires:
- Manual SSLContext configuration
- More complex setup
- Less mature than reverse proxy approach

**This is not recommended**. Use a reverse proxy instead for better security, performance, and maintainability.
