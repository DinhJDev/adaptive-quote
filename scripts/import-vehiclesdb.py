import csv
import hashlib
import io
from pathlib import Path
from urllib.request import urlopen

VERSION = "2026.11.10"
CSV_SHA256 = "1c7776461077eda89ddc6434745a4ada92458c477726f0942abbf95966af1243"
RELEASE = f"https://github.com/vehiclesdb/vehiclesdb/releases/download/v{VERSION}"
ROOT = Path(__file__).resolve().parents[1]
VEHICLE_KINDS = {"car", "van", "truck"}
COLUMNS = ("make_name", "model_name", "aliases", "year_start", "year_end")


def download(asset):
    with urlopen(f"{RELEASE}/{asset}", timeout=30) as response:
        return response.read()


def main():
    source = download("vehicles.csv")
    if hashlib.sha256(source).hexdigest() != CSV_SHA256:
        raise SystemExit("VehiclesDB source checksum does not match the pinned release.")

    reader = csv.DictReader(io.StringIO(source.decode("utf-8-sig"), newline=""))
    rows = {
        tuple(" ".join(row[column].split()) for column in COLUMNS)
        for row in reader
        if row["kind"] in VEHICLE_KINDS
    }
    notices = download("ATTRIBUTION.md").decode("utf-8")
    output = ROOT / "backend/src/main/resources/catalog" / f"vehiclesdb-{VERSION}.tsv"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as file:
        file.write(f"# VehiclesDB {VERSION} · CC BY 4.0 · https://vehiclesdb.com\n")
        file.write("# Filtered to car, van, and truck records. See vehicle-data-attribution.md.\n")
        writer = csv.writer(file, delimiter="\t", lineterminator="\n", quoting=csv.QUOTE_NONE, quotechar=None)
        writer.writerows(sorted(rows))

    public = ROOT / "frontend/public"
    (public / "vehicle-data-attribution.md").write_text(
        f"Vehicle data by [VehiclesDB](https://vehiclesdb.com), [release {VERSION}]"
        f"(https://github.com/vehiclesdb/vehiclesdb/releases/tag/v{VERSION}). "
        "Licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). "
        "Adaptive Quote filters and reformats the nameplate, alias, and year fields from the release.\n\n"
        + notices,
        encoding="utf-8",
        newline="\n",
    )
    print(f"Imported {len(rows)} VehiclesDB records from v{VERSION}.")


if __name__ == "__main__":
    main()
