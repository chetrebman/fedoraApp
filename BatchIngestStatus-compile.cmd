rem unix
java -Xmx256M -cp "./src:./gwt-build-libraries/gwt-incubator-20100204-r1747.jar:./gwt-build-libraries/gwt-2.0.3/gwt-user.jar:./gwt-build-libraries/gwt-2.0.3/gwt-dev.jar" com.google.gwt.dev.Compiler -workDir "./WebContent" edu.du.penrose.systems.fedoraApp.web.gwt.batchIngest.BatchIngestStatus

rem windows version - : replaced with ;
rem java -Xmx256M -cp "./src;./gwt-build-libraries/gwt-incubator-20100204-r1747.jar;./gwt-build-libraries/gwt-2.0.3/gwt-user.jar;./gwt-build-libraries/gwt-2.0.3/gwt-dev.jar" com.google.gwt.dev.Compiler -workDir "./WebContent" edu.du.penrose.systems.fedoraApp.web.gwt.batchIngest.BatchIngestStatus

echo "Move contents from war directory to the WebContent"
cp -R ./war/* ./WebContent

echo "Remove war directory "
rm -R war

echo 

