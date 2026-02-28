# ISO 6523 PDF to CSV

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Java console application that reads the ISO 6523 ICD list PDF and produces three CSV files:

- [icd_summary.csv](src/test/resources/references/icd_summary.csv) – two-column list of ICDs and scheme names (pages 1–7)
- [icd_summary_natural_order.csv](src/test/resources/references/icd_summary_natural_order.csv) – Summary with ICDs sorted in natural (numeric) order
- [icd_details.csv](src/test/resources/references/icd_details.csv) – one row per ICD with all documented fields (pages 8+)
- [icd_combined.csv](src/test/resources/references/icd_combined.csv) – details with Name of Scheme and comparison column (joined from summary)

## PDF source

The official ICD list is available from the [ISO/IEC 6523 Registration Authority](http://iso6523.info/):

- **Last remote PDF downloaded:** [icd_list_2025-11-11.pdf](src/test/resources/icd_list_2025-11-11.pdf) ([source](http://iso6523.info/icd_list.pdf))
- **Last remote website date:** 2025-11-08

The tool checks on each run whether the local PDF matches the online version. If a newer version is available, it is downloaded with a date suffix into the resources folder; that dated file is used for conversion.

## Requirements

- **JDK 25**
- Maven 3.x

## Build and run

```bash
mvn clean install
```

## License

This project is licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

See the [LICENSE](LICENSE) file for the full text.

