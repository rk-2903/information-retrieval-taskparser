# Text Parser – Programming Assignment 1

CSCE 5200 – Information Retrieval and Web Search

## Author

Name: (Rahul Kumar)
Course: CSCE 5200 – Information Retrieval and Web Search

---

## Project Overview

This project implements a **Text Parser** for an Information Retrieval (IR) system. The parser processes TREC documents, performs tokenization, removes stopwords, applies Porter stemming, and generates:

* **Term Dictionary:** Maps each unique term to a unique term ID
* **Document Dictionary:** Maps each document name to a document ID

The output is stored in `parser_output.txt`.

---

## Folder Structure

Ensure the following files and folders are in the same project directory:

```
ProjectFolder/
│
├── TextParser.java
├── Porter.java
├── stopwordlist.txt
├── ft911/
│   ├── ft911_1
│   ├── ft911_2
│   └── ...
└── parser_output.txt (generated after execution)
```

---

## Requirements

* Java JDK 8 or higher
* Command line / terminal access

---

## How to Compile

Open a terminal in the project directory and run:

```
javac Porter.java TextParser.java
```

This will generate the `.class` files.

---

## How to Run

Execute the parser using:

```
java TextParser
```

---

## Output

After successful execution, the program generates:

```
parser_output.txt
```

The file contains:

1. **Term Dictionary**

```
aa	1
aaa	2
aachen	3
aaf	4
aah	5
aakvaag	6
...
```

2. **Document Dictionary**

```
FT911-1	1
FT911-2	2
FT911-3	3
FT911-4	4
FT911-5	5
...
```

---

## Processing Steps Implemented

The parser performs the following preprocessing steps:

1. Tokenization (split on non-alphanumeric characters)
2. Lowercase conversion
3. Removal of tokens containing numbers
4. Stopword removal
5. Porter stemming
6. Term dictionary generation
7. Document dictionary generation

---

## Notes

* All paths are **relative**, so the project should run without modification.
* Input files must be located inside the **ft911** folder.
* The program automatically processes all files matching `ft911_*`.

