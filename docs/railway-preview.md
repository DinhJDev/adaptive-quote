# Railway preview

Use the existing `adaptive-quotes` project and its `app` service in `preview`. Keep secrets in Railway's variables.

1. Add a PostgreSQL service in the same environment and confirm it has a volume. Use Railway's private DB hostname and leave the public PostgreSQL TCP proxy disabled. Complete the database roles and schema setup below before deploying the app.
2. Give `app` a Railway HTTPS domain. Set `QUOTE_PUBLIC_ORIGIN` to its exact HTTPS origin without a trailing slash. Set `QUOTE_SESSION_SECRET` to a stable, unique random value of at least 32 bytes so saved quotes can reopen after a deployment.
3. Set `SPRING_DATASOURCE_URL` to `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}?currentSchema=adaptive_quote`, replacing `Postgres` with the database service name. Set `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` for the runtime role below. Set `SPRING_FLYWAY_URL` to the same JDBC URL and configure `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD` for the separate migration role.
4. Deploy the repository root to `app` using the Dockerfile. Railway supplies `PORT`. Set the service's health check to `/actuator/health`. Check resource usage after startup.

[Railway PostgreSQL](https://docs.railway.com/databases/postgresql) · [Railway variable references](https://docs.railway.com/variables/reference) · [Railway deploy health checks](https://docs.railway.com/deployments/healthchecks)

## Database roles

Use a private admin connection to the same preview database named in the Flyway JDBC URL. Create the two login roles and set their passwords using your database client's password facility. Create the `adaptive_quote` schema before the first deploy and give ownership to `quote_migrations`. Flyway uses that schema to create its history and quote tables.

~~~sql
CREATE ROLE quote_migrations LOGIN;
CREATE ROLE quote_app LOGIN;
CREATE SCHEMA adaptive_quote AUTHORIZATION quote_migrations;
GRANT USAGE ON SCHEMA adaptive_quote TO quote_app;
~~~

If startup reports `Unable to create schema "adaptive_quote"` with SQL state `42501`, check whether the schema exists in the preview database and is owned by `quote_migrations`. Run the schema setup as the database admin, then redeploy.

After Flyway creates the table, grant the runtime role access to receipts. The migration role retains ownership and can perform the retention purge.

~~~sql
GRANT SELECT, INSERT ON adaptive_quote.quotes TO quote_app;
~~~

## Optional live extraction

Use a dedicated OpenAI project for this preview and allow GPT-6 Luna. In the project's Limits settings, enable the hard spend limit at $4 with an alert below it. OpenAI tracks spend asynchronously. The application's own budget check runs before each call.

In Railway's `preview → app → Variables`, enter the project key as `OPENAI_API_KEY` and set `QUOTE_AI_ENABLED=true`. Deploy, verify that `GET /api/v1/catalog` reports `liveEnabled: true`, and try a synthetic description in the browser. Check that the intake source says **OpenAI extraction**, the modifications are editable, and Spring returns the price after selection. Set `QUOTE_AI_ENABLED=false` to switch the preview back to catalog matching. Keep this project's key and budget dedicated to this app.

The app allows one live request every ten seconds across replicas and caps PostgreSQL reservations at $4 per UTC month. It reserves $0.28 before calling the model and reduces that reservation for validated responses using conservative token rates. Failed, interrupted, and unmetered calls retain the full reservation. The model, tier, and output limit are fixed in code. Review the model's rates before February 1, 2027. Live calls stop on that date until the rates and review date are updated. The budget table must survive redeployments and must be preserved during restore. Flyway grants the preview runtime role read and write access to that table.

Only the vehicle description is sent to OpenAI, with response storage disabled. Use synthetic descriptions. The app diverts some common contact patterns to catalog lookup, and the UI asks users to omit personal details. Application logs record status, timing, and usage counts.

[OpenAI project spend and model settings](https://help.openai.com/en/articles/9186755-managing-projects-in-the-api-platform) · [GPT-6 Luna pricing](https://developers.openai.com/api/docs/models/gpt-6-luna)

## Preview checks

Complete a fixture, reload and resume it, then save and reopen the receipt in the same browser. Replay the same save with its original quote ID and body and confirm the response changes from `201` to `200`. Check that a second browser session cannot open the receipt. Redeploy with the same signing secret and verify the original browser can still open it. Use a privileged maintenance connection to purge expired rows.

Copy a link from Quote Ready and open it in another browser. The link uses `QUOTE_PUBLIC_ORIGIN` and gives anyone who has it access to the vehicle, confirmed part names, and recommendation for seven days. Creation requires the configured origin and a quote session. Flyway creates the snapshot table and grants read and insert access to `quote_app`.

~~~sql
DELETE FROM adaptive_quote.quotes WHERE expires_at <= now();
DELETE FROM adaptive_quote.quote_links WHERE expires_at <= now();
~~~
