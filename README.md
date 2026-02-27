# ISO 6523 PDF to CSV

Java console application that reads the ISO 6523 ICD list PDF and produces two CSV files:

- `target/icd_summary.csv` – two-column list of ICDs and scheme names (pages 1–7)
- `target/icd_details.csv` – one row per ICD with all documented fields (pages 8+)

## PDF source

The official ICD list is available from the [ISO/IEC 6523 Registration Authority](http://iso6523.info/):

- **PDF:** [http://iso6523.info/icd_list.pdf](http://iso6523.info/icd_list.pdf)
- **Website updated:** 2025-11-08

When using the default path `src/test/resources/icd_list.pdf`, the tool checks on each run whether the local PDF matches the online version. If a newer version is available, it is downloaded as `icd_list_2025-11-08.pdf` (date reflects the website’s last update); the original `icd_list.pdf` is left unchanged.

## Requirements

- **JDK 25**
- Maven 3.x

## Build and run

```bash
mvn clean package
mvn -q exec:java
```

By default, the input PDF is `src/test/resources/icd_list.pdf` (or `icd_list_2025-11-08.pdf` if a new version was downloaded) and output goes to `target`. Optionally specify paths:

```bash
mvn -q exec:java -Dexec.args="path/to/icd_list.pdf path/to/output-dir"
```

