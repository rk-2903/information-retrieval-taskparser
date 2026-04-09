CSCE 5200 — Information Retrieval

Project Phase 2: Search Engine Index Construction

Author: Rahul Kumar

--------------------------------------------------------------------------------
OVERVIEW
--------------------------------------------------------------------------------
The report outlines the design and implementation of the Indexer part of an Information Retrieval (IR) engine, which was developed as Phase 2 of a course project. The Indexer is fed a collection of documents in TREC format and gives as output two basic indexes (a Forward Index and an Inverted Index) on which Phase 3 query retrieval will be based.
This system is written in Java and uses the same preprocessing pipeline as in Phase 1 (TextParser): lowercasing, tokenisation, filtering out digits, removing stop-words, and Porter stemming.2. Architecture & Design

Output files produced:
```term_dict.txt      — stemmedTerm <TAB> termID
doc_dict.txt       — DOCNO <TAB> docID
forward_index.txt  — docID: termID:freq; termID:freq; ...
inverted_index.txt — termID: docID:freq; docID:freq; ...
```
--------------------------------------------------------------------------------
FILES INCLUDED
--------------------------------------------------------------------------------
Indexer.java       Main indexer program (Phase 2)
Porter.java        Porter stemmer (reused from Phase 1)
readme.txt         This file
report.docx        Project report

--------------------------------------------------------------------------------
PREREQUISITES
--------------------------------------------------------------------------------
Java 11 or later  (tested with Java 17)

Verify your Java version:
java -version

--------------------------------------------------------------------------------
COMPILE
--------------------------------------------------------------------------------
Place all .java files in the same directory, then run:

#### javac Porter.java Indexer.java

--------------------------------------------------------------------------------
TO RUN
--------------------------------------------------------------------------------
Usage:
#### java Indexer `<corpusDir_Path>` `<stopwordFile_Path>` `<outputDir_Path>`

Arguments:
```
corpusDir      Path to the folder containing the TREC corpus files (e.g. ft911/)
stopwordFile   Path to the stop-word list file (e.g. stopwordlist.txt)
outputDir      Path to the folder where output files will be written.
               The folder is created automatically if it does not exist. (e.g. output_phase2/)
```
Example:
java Indexer ft911 stopwordlist.txt output_phase2

--------------------------------------------------------------------------------
EXPECTED DIRECTORY LAYOUT BEFORE RUNNING
--------------------------------------------------------------------------------

```
your_project_folder/
├── Indexer.java
├── Porter.java
├── stopwordlist.txt
└── ft911/
    ├── ft911_1
    ├── ft911_2
    └── ...  (all TREC corpus files)
```
--------------------------------------------------------------------------------
EXPECTED OUTPUT
--------------------------------------------------------------------------------
After a successful run you will see console output similar to:

Stop words loaded: 523
Indexing time (ms)        : 1374
forward_index.txt  (bytes): 5486324
inverted_index.txt (bytes): 5883519
Documents indexed         : 5368
Unique terms              : 36247

Query mode (type ':q' to exit)
Enter a term to see its inverted-index postings (DOCNO / docID : freq):

Enter any term: 


```
And the output folder will contain:
output_phase2/
├── term_dict.txt
├── doc_dict.txt
├── forward_index.txt
└── inverted_index.txt
```

--------------------------------------------------------------------------------
INTERACTIVE QUERY MODE
--------------------------------------------------------------------------------
After indexing completes the program enters an interactive query loop.

- Type any English word and press Enter.
- The program applies the same preprocessing (lowercase, stop-word check,
  Porter stemming) used during indexing, then looks up the term in the
  inverted index and prints every document it appears in along with its
  frequency.
- Type :q and press Enter (or press Ctrl+D) to exit.

Example:
```
Enter any term: Carbon
 
Term='Carbon'  stemmed='carbon'  termID=401  appears in 36 doc(s):

carbon(401): 6:1; 28:1; 39:1; 136:1; 241:1; 486:2; 566:2; 801:1; 997:1; 1052:1; 1086:3; 1358:1; 1911:1; 1952:2; 2166:1; 2198:4; 2223:1; 2698:3; 2751:1; 2967:2; 3179:1; 3902:2; 3908:2; 3913:1; 4339:1; 4434:1; 4521:2; 4555:3; 4723:1; 4933:1; 4970:3; 5013:1; 5086:1; 5092:1; 5093:1; 5223:1;
```

```
Enter any term: the
Dropped — 'the' is a stop word.
```