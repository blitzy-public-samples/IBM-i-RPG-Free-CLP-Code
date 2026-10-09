# Customer test data: city/state/ZIP file

This folder supplies an optional full city/state/ZIP (CSZ) file to the customer test-data generator. Docker Compose mounts it read-only at `/data` in the `generator` service (profile `tools`). `*.csv` files here are git-ignored, so a download is never committed. The generator itself is described under [Loading test data](../README.md#loading-test-data) in the main README.

## Default: the bundled sample

With no file supplied, the generator reads `classpath:generator/csz-sample.csv`, which is [`backend/customer-api/src/main/resources/generator/csz-sample.csv`](../backend/customer-api/src/main/resources/generator/csz-sample.csv). It has 200 rows, at least one for each of the 50 states, DC and PR, and every city is 20 characters or fewer. That is enough for the default 300-row load and for 1,000,000 rows, but cities repeat heavily. Supply a full file for a realistic distribution.

## CSV layout

- A UTF-8 CSV file with a header row. Fields may be double-quoted, so commas inside quotes are kept.
- Required columns: `zip`, `state`, and either `city` or its alias `primary_city`. Header names match case-insensitively; when both city columns are present, `city` is used.
- `type` is accepted. Every other column is ignored.

The canonical layout is `zip,type,city,state`:

```csv
zip,type,city,state
00501,UNIQUE,HOLTSVILLE,NY
10001,STANDARD,NEW YORK,NY
94105,STANDARD,SAN FRANCISCO,CA
```

`zip` is numeric: digits only, with or without leading zeros. Leading zeros that a spreadsheet drops are restored, because the stored ZIP is zero-padded. A blank or non-numeric `zip` fails the whole load.

## What the generator does with each row

| Step | Rule | Origin |
|------|------|--------|
| 1 | A row whose trimmed city is longer than 20 characters is dropped, because `custmast.city` holds 20. | LOADCUSTR |
| 2 | The city is uppercased, and the state is trimmed. | LOADCUSTR |
| 3 | A row whose state is not one of the 58 codes in the STATES table is dropped. | **New** |
| 4 | The ZIP is stored as the last five digits of `zip`, zero-padded to five: `501` becomes `00501`. | LOADCUSTR |
| 5 | Each generated customer takes its city, state and ZIP from one retained row chosen at random. | LOADCUSTR |

Rule 3 is new because the target database has a foreign key from `custmast.state` to `states`, which the IBM i table did not have. STATES holds the 50 states plus AA, AE, AS, DC, GU, MP, PR and VI, so rows of the downloaded file with codes such as AP, FM, MH and PW are dropped.

The CSV is only read. The generator never writes to `/data`.

## Where to obtain a full file

The original readme links the ZIP code database at <https://www.unitedstateszipcodes.org/zip-code-database/>. That is a third-party database, not a USPS file, although the original readme calls it a USPS download. This is discrepancy D7 in [Deviations and open questions](../docs/deviations-and-open-questions.md).

- Download it manually in a browser: choose the free version, complete the site's licence-terms form, and pick the CSV format (`zip_code_database.csv`). The site's bot protection refuses scripted downloads such as `curl` or `wget`, and it may block some networks with HTTP 403. Then save the file on the host as `customer-master/data/csz.csv`, as described under [How to use it](#how-to-use-it).
- The unedited download works directly. From its header the generator reads `zip`, `type`, `primary_city` (accepted as `city`) and `state`; it ignores the other columns, such as `county` and `latitude`. Unlike the IBM i procedure, no column needs to be deleted or renamed.
- If you obtain it as a spreadsheet, save it as a UTF-8 CSV without editing the columns. A file in another encoding fails the load.
- Check the site's licence and terms of use yourself before you download or redistribute the data. In October 2026 the site offered the free version for personal or educational, non-commercial use only, without redistribution.

## How to use it

Save the file as `customer-master/data/csz.csv`. It must be world-readable, because the generator image runs as a non-root user. Then, from `customer-master/`, run:

```sh
docker compose --profile tools run --rm generator --csz-file=/data/csz.csv
```

To load 1,000,000 customers from it:

```sh
docker compose --profile tools run --rm generator --csz-file=/data/csz.csv --count=1000000
```

`--csz-file` takes the path **inside the container** (`/data/...`), not the host path. Instead of the flag, you can set `GENERATOR_CSZ_FILE=/data/csz.csv` in `.env` (template: [`.env.example`](../.env.example)). A command-line flag wins over its `GENERATOR_*` variable, which wins over the default. The other options (`--count`, default 300; `--start-id`; `--seed`) are documented under [Loading test data](../README.md#loading-test-data).

Before you run it:

- A load replaces every row in `custmast`, including the 300 seed rows. To get the seed rows back, run `docker compose down -v` and start the stack again.
- The load needs `db` and `app`. Compose starts them first if they are not running and waits until `app` is healthy ([`docker-compose.yml`](../docker-compose.yml)).
- A CSV problem, such as a missing or unreadable file, a missing required column or invalid UTF-8, makes the generator print an error message and exit with status 1. A missing file never falls back to the bundled sample. The file is read before anything is written, so `custmast` is left unchanged.

## Source mapping

This file replaces the IBM i CSZ table, which was uploaded from the spreadsheet with IBM i Access Client Solutions and read by LOADCUSTR. The original procedure is in [`5250_Subfile/README.md`](../../5250_Subfile/README.md).
