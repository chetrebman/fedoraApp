FedoraApp - November 13 2012

fedoraApp is a Fedora Commons Digital Repository METS/MODS Batch Ingester

FedoraApp is written using Java, Spring and GWT technologies.

The distribution is a complete Eclipse project.

All source is in the src directory.

In hindsight I wish I had used another technology then GWT, it greatly complicates the distribution, but it worked at the time.
If you need to recompile, see the file BatchIngestStatus-compile.cmd it uses GWT version 2.0.3

FedoraApp is a stand alone application, for manually ingesting batch files into Fedora.

FedoraApp works with fedoraProxy to perform remote ingests. 

FedoraApp has been used extensively by The Colorado Alliance of Research Libraries and is reliable. However being a single person rather then a software department, I haven't had
to time to extensively document the big picture, so I choose to create a working example instead.
Therefore even if you intend to run fedoraApp stand-alone, it is recommended you look at the github download that contains
jetty along with both fedoraApp and fedoraProxy and look at the sample batch files and tutorial.

Check out the doc directory for the API

Happy Ingesting!

..Chet Rebman