The words 'topic' and 'query' are interchangable.


Description and Format of 'qrels' file
--------------------------------------

The 'main.qrels' file consists of relevance judgements (manually judged)
for each of the topics (queries) present in topics.txt file.

This file will be used to determine the performance of your system.

**Do not change the contents of the 'main.qrels' file.**

The format of the 'main.qrels' file is as follows:

TOPIC    ITERATION    DOCUMENT    RELEVANCY 

where 
	TOPIC is the topic number,
	ITERATION is the feedback iteration (almost always zero 
			and not used),
	DOCUMENT is the document name that corresponds to the  
			"docno" field in the documents, and
	RELEVANCY is a binary code of 0 for not relevant and 1 for 
			relevant.

Note that not all the documents are manually judged to find out
if it is relevant/irrelevant to a topic. Hence, you can assume
that if the document name is not present in the file for
any topic, then that document is irrelevant for our evaluations.


Description and Format of the 'topics' file
---------------------------------------------------

The format of the file that contains the queries is very much
similar to the documents - only difference
is that the tags are different. 

Each topic is contained within the <top> and </top> tags.

The format of each topic (query) is as follows:

<num> Unique Query Number
<title> Main Query (Max. three words)
<desc> One sentence description of the query
<narr> Concise description of what makes a document relevant

For the retrieval model, you can use the Main
Query, i.e. the <title> tag. The <desc> and <narr> can be used for
query expansion or to improve the precision of your system.


Description and Format of the output of your Query Processor
------------------------------------------------------------

Your Query Processor should process all the topics (queries) in
batch mode, and output a file that has the following format:

TOPIC    DOCUMENT    UNIQUE#    COSINE_VALUE   
where
	TOPIC is the topic number,
	DOCUMENT is the document name that corresponds to the  
			"docno" field in the documents
	UNIQUE# is a unique counter of the number of documents
			that were retrieved for each topic
	COSINE_VALUE is the cosine similarty score for each document with respect to the
			topic  (using TF*IDF as weighting scheme).
	

****Every column (field) above is separated by a TAB.****

Look at the 'sample_output.txt' to get an idea of how the output
of your Query Processor should look like. 



