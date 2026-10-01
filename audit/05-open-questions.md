# Open questions and evidence needed

Each item below is **[UNKNOWN]** because the repository does not establish it.

1. What is the intended operating classification: local demonstration, research tooling, paper trading, or regulated production execution? Provide the approved service boundary and owners.
2. Who can access the dashboard/API/WebSocket, and is any reverse proxy, TLS termination, authentication, WAF, firewall or network segmentation deployed outside this repository? Provide deployed configuration and an access diagram.
3. Have the credentials committed in `fetch_real_api_data.py` been revoked, and are they allowed by their providers to be used from this code? Provide rotation evidence and secret-management policy.
4. Which exchange, market-data and clearing agreements are active? The repository supplies simulated EOBI/ETI/Prisma labels but no schema, certification, replay entitlement, contract master, or clearing specification.
5. What pricing conventions are authoritative (day count, calendar, settlement, corporate actions, rates/dividends, exercise styles, currency, rounding)? Provide golden market cases and model-validation tolerances.
6. What are the actual risk limits, margin methodology, risk governance, stop-trading procedures and kill-switch acknowledgements? Provide signed control design and test records.
7. What trade/order/position records must survive restarts, what retention rules apply, and what recovery point/time objectives are required? No persistent model or backup configuration is present.
8. What load, availability, latency and user targets are required for the next 12 months? Provide observed production metrics or capacity goals.
9. Which JDK/toolchain is supported in production? Local audit build used JDK 26.0.1, while Docker declares Java 21 and no reproducible Java build descriptor exists.
10. Are the generated bundles in `web/` the intended user-facing dashboard, or is `web-react/`? Provide the production frontend build/publish path and security ownership for CDN resources.
