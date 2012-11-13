/*
 * Copyright 2012 University of Denver
 * Author Chet Rebman
 * 
 * This file is part of FedoraApp.
 * 
 * FedoraApp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * FedoraApp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with FedoraApp.  If not, see <http://www.gnu.org/licenses/>.
 */

package edu.du.penrose.systems.fedoraApp.util;

import java.io.*;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.axis.types.NonNegativeInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jdom.Element;
import org.jdom.filter.Filter;
import edu.du.penrose.systems.exceptions.FatalException;

import edu.du.penrose.systems.fedoraApp.FedoraAppConstants;
import edu.du.penrose.systems.fedoraApp.batchIngest.data.BatchIngestOptions;
import edu.du.penrose.systems.fedoraApp.batchIngest.data.ThreadStatusMsg;


/**
 * Split a large file containing multiple METS sections into multiple METS files.
 * The new files containing a single METS record are saved 
 * in the batch ingests METS file folder. The original input file does not have to
 * validate since we are simply streaming through the file and mathching <mets>
 * </mets> elements. The <mets></mets> elements and everything they contain 
 * are written to a single METS XML file. The file name is autogenerated and is
 * Guaranteed to be unique. The new METS XML files are also checked or 
 * validated, this occurs during the actual ingest.
 * 
 * @author chet.rebman
 *
 */
public class MetsBatchFileSplitter {

	public static final String QUOTE = "\"";
	public static final String APOST = "\'";

	/** 
	 * Logger for this class and subclasses.
	 */
	protected static final Log logger = LogFactory.getLog( "edu.du.penrose.systems.fedoraApp.util.MetsBatchFileSplitter" );
	
	private static final char SPACE = '\u0020';
	private static final String BATCH_ELEMENT                    = "<batch>";
	private static final String BATCH_ELEMENT_MARKER             = "<batch" + SPACE;
	private static final String BATCH_DESCRIPTION_ELEMENT_MARKER = "<batchDescription" + SPACE;
	private static final String INGEST_CONTROL_ELEMENT_MARKER    = "<ingestControl" + SPACE;

	static public enum BatchType 
	{
		NEW, UPDATES, MIXED;  
	}

	public enum ingestDataType { ALL, META, PCO }; // all means METS contains meta data and PCOs (data objects)

	private MetsBatchFileSplitter( ) {
		// nop
	}

	/**
	 * Get the OBJID value, it can be empty ie OBJID=""
	 * @param metsElementLine
	 * @return the OBJID attribute value
	 * @throws FatalException
	 */
	static public String getObjID( String metsElementLine ) throws FatalException
	{
		String objID = "OBJID NOT FOUND";

		int beginIndex = metsElementLine.indexOf( "OBJID" );
		if ( beginIndex == -1 )
		{
			String errorMsg = "No OBJID!:"+ metsElementLine;
			logger.error( errorMsg );
			throw new FatalException( errorMsg );
		}

		int  endIndex        = metsElementLine.substring( beginIndex+7 ).indexOf( QUOTE );
		if ( endIndex == -1 ){ metsElementLine.substring( beginIndex+7 ).indexOf( APOST ); }
		
		objID = metsElementLine.substring( beginIndex+7, beginIndex + 7 + endIndex );

		return objID;
	}

	/**
	 * Replace the OBJID value, within the <mets:mets> line,  with the reservedPid value..
	 * 
	 * @param metsElementLine
	 * @param reservedPid
	 * @return the modified <mets:mets> line
	 * @throws FatalException
	 */
	static public String  putPidInMetsLineOBJID( String metsElementLine, String reservedPid ) throws FatalException
	{
		String objID = "OBJID NOT FOUND";

		int beginIndex = metsElementLine.indexOf( "OBJID" );
		if ( beginIndex == -1 )
		{
			String errorMsg = "No OBJID attribute!:"+ metsElementLine;
			logger.error( errorMsg );
			throw new FatalException( errorMsg );
		}

		int  endIndex        = metsElementLine.substring( beginIndex+7 ).indexOf( QUOTE );
		if ( endIndex == -1 ){ metsElementLine.substring( beginIndex+7 ).indexOf( APOST ); }

		String objIDvalue = metsElementLine.substring( beginIndex+7, beginIndex + 7 + endIndex );

		metsElementLine = metsElementLine.replace( "OBJID="+QUOTE+objIDvalue+QUOTE, "OBJID="+QUOTE+reservedPid+QUOTE );
		metsElementLine = metsElementLine.replace( "OBJID="+APOST+objIDvalue+APOST, "OBJID="+APOST+reservedPid+APOST );

		return metsElementLine;
	}

	/**
	 * Split a file with multiple METS sections into separate files each containing
	 * a single METS record. The input file must contain one or more multiple
	 * <mets></mets> elements (sections). if true a OBJID must exist in every <mets> 
	 * element, this will become the file name. Otherwise a unique file name is 
	 * generated. Return true if the <batch update="true"> is set in the batch file other
	 * wise return false (defaults to new file being ingested ).
	 * 
	 * @see edu.du.penrose.systems.fedoraApp.batchIngest.bus.BatchIngestThreadManager#setBatchSetStatus(String, String)
	 * @param threadStatus this object receives status updates while splitting the file.
	 * @param  inFile file to split
	 * @param  metsNewDirectory directory containing METS for new objects.
	 * @param  metsUpdatesDirectory directory containing METS for existing objects.
	 * @param nameFileFromOBJID if true a OBJID must exist in every <mets> element, this will become the file name. Otherwise a unique file name is 
	 * generated
	 * @param checkValidDiscoveryID if true check that the OBJID contains a valid discovery ID
	 * 
	 * @throws Exception on any IO error.
	 */
	static public void splitMetsBatchFile( BatchIngestOptions ingestOptions, ThreadStatusMsg threadStatus, File inFile, String metsNewDirectory, String metsUpdatesDirectory, boolean nameFileFromOBJID ) throws FatalException
	{    
		String metsDirectory = null; // will get set to either the new directory or the updates directory.
		String batchCreationDate = null;
		StringBuffer batchDescription = null;
		boolean isUpdate = false;
		ingestDataType dataType = ingestDataType.ALL;

		FileInputStream batchFileInputStream;
		try 
		{
			batchFileInputStream = new FileInputStream( inFile );
		} 
		catch (FileNotFoundException e ) 
		{
			throw new FatalException( e .getMessage() );
		}
		DataInputStream  batchFileDataInputStream = new DataInputStream( batchFileInputStream );
		BufferedReader   batchFileBufferedReader  = new BufferedReader( new InputStreamReader(batchFileDataInputStream) );
		File              outFile              = null;
		FileOutputStream  metsFileOutputStream = null; 
		BufferedWriter    metsBufferedWriter   = null;

		String oneLine = null;
		String documentType = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
		int fileCount = 0;
		boolean isBatchFile = false;

		try {
			boolean done = false;
			while ( batchFileBufferedReader.ready() && ! done ) {

				oneLine = batchFileBufferedReader.readLine();
				if ( oneLine.contains(  "<?xml version" )) {
					documentType = oneLine; 
				}
				if ( oneLine.contains( BATCH_ELEMENT ) || oneLine.contains(  BATCH_ELEMENT_MARKER ) )
				{
					isBatchFile = true;
					if ( oneLine.contains( "version="+QUOTE+2+QUOTE ) || oneLine.contains(  "version="+APOST+2+APOST ) )
					{  		
						splitMetsBatchFile_version_2( ingestOptions, threadStatus, inFile, metsNewDirectory, metsUpdatesDirectory, nameFileFromOBJID, null, null, null, null );
						done = true;
					}
					else 
					{
						splitMetsBatchFile_version_1( ingestOptions, threadStatus, inFile, metsNewDirectory, metsUpdatesDirectory, nameFileFromOBJID );
						done = true;
					}
				}
			} // while

		}
		catch (NullPointerException e){
			String errorMsg = "Unable to split files, Permature end of file: Corrupt:"+inFile.toString()+" ?";
			throw new FatalException ( errorMsg );
		}
		catch ( Exception e ) {
			String errorMsg = "Unable to split files: "+e.getMessage();
			logger.fatal( errorMsg );
			throw new FatalException( errorMsg );
		}
		finally {
			try {
				if ( batchFileBufferedReader != null )
				{		
					batchFileBufferedReader.close();		
				}
				if ( metsBufferedWriter != null )
				{
					metsBufferedWriter.close();       
				}  
				if ( ! isBatchFile )
				{
					throw new FatalException( "ERROR: missing <batch> element!");
				}
			} catch ( IOException e ) 
			{
				throw new FatalException( e .getMessage() );
			}
		}

	} // split

	/**
	 * Parse the batch ingest command line and store the command in the returned BatchIngestOption object.
	 * 
	 * @param ingestOptions
	 * @param batchCommandFile
	 * @return the passed in ingestOptions
	 * @throws FatalException
	 */
	public static BatchIngestOptions setCommandLineOptions( BatchIngestOptions ingestOptions, File batchCommandFile ) throws FatalException 
	{ 
		FileInputStream batchFileInputStream      = null;
		DataInputStream  batchFileDataInputStream = null;
		BufferedReader   batchFileBufferedReader  = null;

		boolean isBatchFile      = false;
		boolean validCommandLine = false;

		try {
			batchFileInputStream                      = new FileInputStream( batchCommandFile );
			batchFileDataInputStream = new DataInputStream( batchFileInputStream );
			batchFileBufferedReader  = new BufferedReader( new InputStreamReader(batchFileDataInputStream) );

			while ( batchFileBufferedReader.ready() ) {

				String documentType = "";
				String oneLine = batchFileBufferedReader.readLine();
				if ( oneLine.contains(  "<?xml version" )) {
					documentType = oneLine; 
				}
				if ( oneLine.contains(  "<batch" )) 
				{
					isBatchFile = true;
				}

				if ( oneLine.contains( ("<ingestControl") ) && oneLine.indexOf( "<!") == -1 )
				{
					if ( ( ! oneLine.contains( "command" )) || ( ! oneLine.contains( "type" )) ){
						throw new FatalException( "Invalid batchDescription" );
					}

					String ingestControlLine = oneLine.trim();
					validCommandLine = parseCommandLine( ingestOptions, oneLine  );
					break;
				}
			} // while

		}
		catch (NullPointerException e){
			String errorMsg = "Unable to split files, Permature end of file: Corrupt:"+batchCommandFile.toString()+" ?";
			throw new FatalException ( errorMsg );
		}
		catch ( Exception e ) {
			String errorMsg = "Unable to split files: "+e.getMessage();
			logger.fatal( errorMsg );
			throw new FatalException( errorMsg );
		}
		finally {
			try {
				if ( batchFileBufferedReader != null )
				{		
					batchFileBufferedReader.close();		
				}
				if ( ! isBatchFile )
				{
					throw new FatalException( "ERROR: missing <batch> element!");
				}
				if ( ! validCommandLine )
				{
					throw new FatalException( "ERROR: missing or Invalid <batch> command line!");
				}
			} catch ( IOException e ) 
			{
				throw new FatalException( e .getMessage() );
			}
		}

		return ingestOptions;
	}


	/**
	 * Split a file with multiple METS sections into separate files each containing
	 * a single METS record. The input file must contain one or more multiple
	 * <mets></mets> elements (sections). if true a OBJID must exist in every <mets> 
	 * element, this will become the file name. Otherwise a unique file name is 
	 * generated. Return true if the <batch update="true"> is set in the batch file other
	 * wise return false (defaults to new file being ingested ).
	 * 
	 * @see edu.du.penrose.systems.fedoraApp.batchIngest.bus.BatchIngestThreadManager#setBatchSetStatus(String, String)
	 * @param threadStatus this object receives status updates while splitting the file.
	 * @param  inFile file to split
	 * @param  metsNewDirectory directory containing METS for new objects.
	 * @param  metsUpdatesDirectory directory containing METS for existing objects.
	 * @param nameFileFromOBJID if true a OBJID must exist in every <mets> element, this will become the file name. Otherwise a unique file name is 
	 * generated
	 * @deprecated
	 * @throws Exception on any IO error.
	 */
	static public void splitMetsBatchFile_version_1( BatchIngestOptions ingestOptions, ThreadStatusMsg threadStatus, File inFile, String metsNewDirectory, String metsUpdatesDirectory, boolean nameFileFromOBJID ) throws FatalException 
	{    
		String metsDirectory = null; // will get set to either the new directory or the updates directory.

		FileInputStream batchFileInputStream;
		try 
		{
			batchFileInputStream = new FileInputStream( inFile );
		} 
		catch (FileNotFoundException e ) 
		{
			throw new FatalException( e .getMessage() );
		}
		DataInputStream  batchFileDataInputStream = new DataInputStream( batchFileInputStream );
		BufferedReader   batchFileBufferedReader  = new BufferedReader( new InputStreamReader(batchFileDataInputStream) );
		File              outFile              = null;
		FileOutputStream  metsFileOutputStream = null; 
		BufferedWriter    metsBufferedWriter   = null;

		String oneLine = null;
		String documentType = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
		int fileCount = 0;

		try {
			while ( batchFileBufferedReader.ready() ) {

				threadStatus.setStatus( "Spliting XML file #: " + fileCount);

				oneLine = batchFileBufferedReader.readLine();
				if ( oneLine.contains(  "<?xml version" )) {
					documentType = oneLine; 
				}
				if ( oneLine.contains(  "<batch" )) {
					if ( oneLine.contains( FedoraAppConstants.BATCH_FILE_UPDATE_MARKER+"="+QUOTE+"true"+QUOTE ) 
					  || oneLine.contains( FedoraAppConstants.BATCH_FILE_UPDATE_MARKER+"="+APOST+"true"+APOST ))
					{  			
						ingestOptions.setBatchIsUpdates( true ); 
						metsDirectory = metsUpdatesDirectory;
					}
					else {  			
						ingestOptions.setBatchIsUpdates( false ); 
						metsDirectory = metsNewDirectory;
					}
				}
				if ( oneLine.contains(  "<mets:mets" )) {

					boolean haveEntireMetsLine = false;
					while ( ! haveEntireMetsLine )
					{
						StringBuffer tempBuffer = new StringBuffer( oneLine );
						String moreOfMetsLine   = null;
						if ( ! oneLine.contains( ">" ))
						{     		
							moreOfMetsLine = batchFileBufferedReader.readLine();
							tempBuffer.append( moreOfMetsLine );

							if ( moreOfMetsLine.contains( ">" ))
							{
								haveEntireMetsLine = true;
								oneLine = tempBuffer.toString();
							}
							else {
								oneLine = tempBuffer.toString();
							}
						}
						else {
							haveEntireMetsLine = true;
						}
					}

					// Name the output file for a single Mets element and its contents.

					if ( nameFileFromOBJID )
					{
						String objID = MetsBatchFileSplitter.getObjID( oneLine );

						outFile = new File( metsDirectory+objID+".xml" );
						logger.info( "outputSplitFile METS file: " + metsDirectory+objID+".xml" );
						if ( outFile.exists() )
						{
							String errorMsg = "OBJID already exists:"+ outFile.getName();
							System.out.println( errorMsg ); 
							logger.error( errorMsg );
							throw new FatalException( errorMsg );
						}
					}
					else 
					{
						outFile = new File( metsDirectory  + edu.du.penrose.systems.util.FileUtil.getDateTimeMilliSecondEnsureUnique()+".xml" );
					}

					logger.info( "outputSplitFile METS file: "+outFile.toString() + "\n\n" );

					metsFileOutputStream = new FileOutputStream( outFile );
					metsBufferedWriter   = new BufferedWriter(new OutputStreamWriter(metsFileOutputStream, "UTF-8"));
					metsBufferedWriter.write( documentType );
					metsBufferedWriter.newLine();

					// This is a version 1 batch file, so write a default version 2 command line for the new ingester        
					metsBufferedWriter.write( FedoraAppConstants.VERSION_ONE_COMMAND_LINE );
					metsBufferedWriter.newLine();

					while ( ! oneLine.contains(  "</mets:mets" )) { // null pointer on premature end of file.
						metsBufferedWriter.write( oneLine );
						metsBufferedWriter.newLine();
						oneLine = batchFileBufferedReader.readLine();
					}
					metsBufferedWriter.write( oneLine );
					metsBufferedWriter.newLine();
					metsBufferedWriter.close();   

					fileCount++;
				}
			} // while

		}
		catch (NullPointerException e){
			String errorMsg = "Unable to split files, Permature end of file: Corrupt:"+inFile.toString()+" ?";
			throw new FatalException ( errorMsg );
		}
		catch ( Exception e ) {
			String errorMsg = "Unable to split files: "+e.getMessage();
			logger.fatal( errorMsg );
			throw new FatalException( errorMsg );
		}
		finally {
			try {
				if ( batchFileBufferedReader != null )
				{		
					batchFileBufferedReader.close();		
				}
				if ( metsBufferedWriter != null )
				{
					metsBufferedWriter.close();       
				}  
			} catch ( IOException e ) 
			{
				throw new FatalException( e .getMessage() );
			}
		}

	} // split

	/**
	 * Split the batchIngest command file. If the batch contains additions put the results into the 'mets/new' directory. If updates put the
	 * files into the 'mets/upates' directory. The ingest command (only one per batchfile!!) is saved in a comment prior to the <mets:mets>
	 * element for each split file. This means the batch can only contain files of one type ie adds or updates.
	 * <br><br>
	 * If an error occurs we will try to remove any generated output file and then throw an exception.
	 * <br>
	 * One the ingest command line is found ie "<ingestControl command="A" type="pidInOBJID" />" ALL other command line are ignored!! AFter that
	 * point we only look for <mets:mets> and </mets:mets> to split the file.
	 * 
	 * NOTE Since this method may need to get fedora pids The following libraries are needed...
	 * wsdl4j-1.5.1.jar
	 * commons-discovery.jar
	 * fcrepo-server-3.4-utilities-main.jar
	 * jaxrpc.jar
	 * logback-core-0.9.18.jar
	 * logback-classic-0.9.18.jar
	 * trippi-1.1.2-core.jar
	 * fcrepo-common-3.4.jar
	 * fcrepo-client-admin-3.4.jar
	 * jdom.jar
	 * 
	 * @param ingestOptions set batchIngestDate, batchDescription, 
	 * @param threadStatus can be null.
	 * @param inFile batch file to split
	 * @param metsNewDirectory
	 * @param metsUpdatesDirectory
	 * @param nameFileFromOBJID create xml file's that are named the same as it's OBJID element. This seems like a good idea but if the file
	 * already exists you will get a error, killing the entire ingest.
	 * @param fedoraUser used for a replyWithPid ingest.     If null we will pull from the batchIngest.properties file.
	 * @param fedoraPassword used for a replyWithPid ingest. If null we will pull from the batchIngest.properties file.
	 * 
	 * @return IF the batch file is an add of type 'replyWithPid' return a map of OBJID and PIDs otherwise return null. NOTE: if the <mets:mets OBJID> element is
	 * empty in the batch file, both the key and the value of the returned map will contain the pid.
	 * 
	 * @throws Exception
	 */
	static public Map<String, String> splitMetsBatchFile_version_2( BatchIngestOptions ingestOptions, ThreadStatusMsg threadStatus, File inFile, String metsNewDirectory, String metsUpdatesDirectory, 
			boolean nameFileFromOBJID, String fedoraHost, String fedoraPort, String fedoraUser, String fedoraPassword ) throws Exception
	{
		Map<String, String> pidMap = null;

		FileInputStream batchFileInputStream;
		try 
		{
			batchFileInputStream = new FileInputStream( inFile );
		} 
		catch (FileNotFoundException e ) 
		{
			throw new FatalException( e .getMessage() );
		}

		DataInputStream  batchFileDataInputStream = new DataInputStream( batchFileInputStream );
		BufferedReader   batchFileBufferedReader  = new BufferedReader( new InputStreamReader(batchFileDataInputStream) );
		String oneLine = null;
		String ingestControlLine = null;
		int fileCount = 0;
		String documentType = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
		String batchCreationDate = null;
		String batchDescription = null;
		String metsDirectory = null; // will get set to either the new directory or the updates directory.
		File              outFile              = null;
		FileOutputStream  metsFileOutputStream = null; 
		BufferedWriter    metsBufferedWriter   = null;

		boolean headerFoundLookOnlyForMetsNow = false;
		
		while ( batchFileBufferedReader.ready() ) 
		{
			oneLine = batchFileBufferedReader.readLine();
			if ( ! headerFoundLookOnlyForMetsNow )
			{
				if ( oneLine.contains(  "<?xml version" ) && oneLine.trim().startsWith("<") ) {
					documentType = oneLine; 
				}
	
				// LOOK FOR BATCH DESCRIPTION
				if ( oneLine.contains(  BATCH_DESCRIPTION_ELEMENT_MARKER ) && oneLine.indexOf( "<!") == -1 )
				{
					int  tempLocation1        = oneLine.indexOf( "batchCreationDate="+QUOTE );
					if ( tempLocation1 == -1 ){ oneLine.indexOf( "batchCreationDate="+APOST ); }
					
					int  tempLocation2 =        oneLine.indexOf( QUOTE, tempLocation1+19 );
					if ( tempLocation2 == -1 ){ oneLine.indexOf( APOST, tempLocation1+19 ); }
					
					batchCreationDate = oneLine.substring( tempLocation1+19, tempLocation2 );
					ingestOptions.setBatchIngestDate( batchCreationDate );
	
					oneLine = batchFileBufferedReader.readLine();
					if ( ! oneLine.contains( "<literal>" ) ){
						throw new FatalException( "Invalid batchDescription" );
					}
					StringBuffer tempBatchDescription = new StringBuffer();
					boolean endBatchDescription = false;
					do {
						tempBatchDescription.append( oneLine );
						if ( oneLine.contains( "</literal>") ){
							endBatchDescription = true;
							batchDescription = tempBatchDescription.toString();
							batchDescription = batchDescription.replace( "<literal>", "" ); // it may all be on one line.
							batchDescription = batchDescription.replace( "</literal>", "" );
						}
						oneLine = batchFileBufferedReader.readLine();
					} while ( ! endBatchDescription );	
					ingestOptions.setBatchDescription( batchDescription.trim() );
				}
	
				// look for batch command at the top of the file, prior to the first <mets:mets>
				if ( oneLine.contains( ( INGEST_CONTROL_ELEMENT_MARKER ) ) && oneLine.indexOf( "<!") == -1 )
				{
					if ( ( ! oneLine.contains( "command" )) || ( ! oneLine.contains( "type" )) ){
						throw new FatalException( "The batch control element must have both command and type attributes" );
					}
	
					ingestControlLine = oneLine.trim();
					boolean validCommandLine = parseCommandLine( ingestOptions, ingestControlLine );
					if (! validCommandLine ){
						throw new Exception( "ERROR: Invalid command line found in batch ingest file:"+inFile+" ,  "+ingestControlLine );
					}
	
					headerFoundLookOnlyForMetsNow = true;
					switch( ingestOptions.getIngestCommand() )
					{
					case UPDATE:
						metsDirectory = metsUpdatesDirectory;
						break;
					case ADD:
						metsDirectory = metsNewDirectory;
						break;	
					default:
						throw new Exception( "ERROR: Invalid ingest command" );
					}
	
				} // if line is ingestControl (command line)
			} 
			else // if-else headerFoundLookOnlyForMetsNow
			{
				if ( oneLine.contains( ( INGEST_CONTROL_ELEMENT_MARKER ) ) && oneLine.indexOf( "<!") == -1 )
				{
					logger.warn( "More than one ingest control line found in batch file! extras will be ignored:"+inFile );
				}
				
				// look for <mets:mets> and get complete <mets:mets> element
				if ( oneLine.contains(  "<mets:mets" ) && oneLine.indexOf( "<!") == -1 )
				{
					boolean haveEntireMetsLine = false;
					while ( ! haveEntireMetsLine )
					{
						StringBuffer tempBuffer = new StringBuffer( oneLine );
						String moreOfMetsLine   = null;
						if ( ! oneLine.contains( ">" ))
						{     		
							moreOfMetsLine = batchFileBufferedReader.readLine();
							tempBuffer.append( moreOfMetsLine );
	
							if ( moreOfMetsLine.contains( ">" ))
							{
								haveEntireMetsLine = true;
								oneLine = tempBuffer.toString();
							}
							else {
								oneLine = tempBuffer.toString();
							}
						}
						else {
							haveEntireMetsLine = true;
						}
					}
	
					// process everything up to </mets:mets>
					String objID = MetsBatchFileSplitter.getObjID( oneLine );	
					if ( nameFileFromOBJID )
					{
						outFile = new File( metsDirectory+objID+".xml" );
						logger.info( "outputSplitFile METS file: " + metsDirectory+objID+".xml" );
						if ( outFile.exists() )
						{
							String errorMsg = "file already exists:"+ outFile.getName();
							System.out.println( errorMsg ); 
							logger.error( errorMsg );
							throw new FatalException( errorMsg );
						}
					}
					else 
					{
						switch ( ingestOptions.getIngestThreadType() ){
						case BACKGROUND:
							// TBD this is probably an error
						case MANUAL:
							outFile = new File( metsDirectory  + edu.du.penrose.systems.util.FileUtil.getDateTimeMilliSecondEnsureUnique()+".xml" );
							break;
						case REMOTE:
							outFile = new File( metsDirectory  
									+ edu.du.penrose.systems.util.FileUtil.getDateTimeMilliSecondEnsureUnique()+FedoraAppConstants.REMOTE_TASK_NAME_SUFFIX+".xml" );
							break;
						}
					}
	
					// oneLine now contains the entire <mets:mets....> line
					logger.info( "outputSplitFile METS file: "+outFile.toString() + "\n\n" );
	
					boolean errorOccurred = false;
					try {
						metsFileOutputStream = new FileOutputStream( outFile );
						metsBufferedWriter   = new BufferedWriter(new OutputStreamWriter(metsFileOutputStream, "UTF-8"));
						metsBufferedWriter.write( documentType );
						metsBufferedWriter.newLine();
	
						switch( ingestOptions.getIngestCommand() )
						{ 
						case ADD:
							switch( ingestOptions.getAddCommandType() )
							{
							case REPLY_WITH_PID:
								// we get one pid at a time, write it to the <mets:mets> line OBJID value and add it to the pidMap
								String[] tempPids = null;
								if ( fedoraPassword == null || fedoraUser == null || fedoraHost == null || fedoraPort == null )
								{
									tempPids  = FedoraAppUtil.getPIDs( ingestOptions.getInstitution(), new NonNegativeInteger("1") ); 
								}
								else {
									tempPids  = FedoraAppUtil.getPIDs( fedoraHost, Integer.valueOf( fedoraPort ), fedoraUser, fedoraPassword, ingestOptions.getInstitution(), new NonNegativeInteger("1") );
								}
								
								String reservedPid = tempPids[0];
								metsBufferedWriter.write( "<!--"+ingestControlLine+"-->\n" );
								if ( pidMap == null ){
									pidMap = new LinkedHashMap<String, String>();
								}					
								oneLine = putPidInMetsLineOBJID( oneLine, reservedPid );
	
								if ( objID.contentEquals( "" ) )
								{
									pidMap.put( reservedPid, reservedPid );
								}
								else {
									pidMap.put( objID, reservedPid );
								}
	
								break;
							case PID_IN_OBJID: 
							case NORMAL:
							default:
								metsBufferedWriter.write( "<!--"+ingestControlLine+"-->\n" );
							}
							break;
						case UPDATE:
							metsBufferedWriter.write( "<!--"+ingestControlLine+"-->\n" );
							break;
						case NOT_SET:
						default:
							throw new Exception( "ERROR: Invalid ingest command" );
						}
	
						// read lines from batch file and write to the new mets file until </mets:mets> line
	
						while ( ! oneLine.contains(  "</mets:mets" )) { // null pointer on premature end of file.
							metsBufferedWriter.write( oneLine );
							metsBufferedWriter.newLine();
							oneLine = batchFileBufferedReader.readLine();
							if ( oneLine == null )
							{
								throw new FatalException ( "Error spliting batch file, missing </mets:mets>" );
							}
						}
						metsBufferedWriter.write( oneLine );
						metsBufferedWriter.newLine();
						metsBufferedWriter.close();   
					}
					catch ( Exception e )
					{
						errorOccurred = true; // for cleanup, see below.
						throw new Exception( e );
					}
					finally {
						metsBufferedWriter.close();   
						if ( errorOccurred )
						{
							outFile.delete(); //clean up.
						}
					}
	
					fileCount++;
					if ( threadStatus != null )
					{
						threadStatus.setStatus( "Spliting XML file #: " + fileCount);
					}
					
				} // if <mets:mets> found (look for another mets section now).
				
			} // if-else ! headerFoundLookOnlyForMetsNow
	
		} // while

		return pidMap; // may be null
		
	} // splitMetsBatchFile_version_2

	/**
	 * Parses a {@code <mets:dmdSec ID="dmdAlliance">} element of type..
	 * <pre>
	 * {@code
	 * <mets:dmdSec ID="dmdAlliance"> 
	 *    <mets:mdWrap MIMETYPE="text/xml" MDTYPE="OTHER" LABEL="Custom Alliance Metadata">
	 *       <mets:xmlData>
	 *           <islandora collection="codu:38046" contentModel="codu:coduBasicObject" />
	 *       </mets:xmlData>
	 *    </mets:mdWrap>
	 * </mets:dmdSec> }
	 * </pre>
	 * @param ingestOptions
	 * @param dmdAllianceElement a {@code <mets:dmdSec ID="dmdAlliance"> element containing an islandora element. }
	 * @throws Exception if IslandoraElement is missing or it's collection or contentModel is missing
	 */
	static public void parseDmdAllianceElement( BatchIngestOptions ingestOptions, Element dmdAllianceElement ) throws Exception
	{
		Iterator<Element> islandorElementIterator = dmdAllianceElement.getDescendants( new IslandoraElementFilter() );
		if ( islandorElementIterator.hasNext() )
		{
			Element islandoraElement = islandorElementIterator.next();
			String collection   = islandoraElement.getAttributeValue( FedoraAppConstants.ISLANDORA_COLLECTION_ATTRIBUTE ).trim();
			String contentModel = islandoraElement.getAttributeValue( FedoraAppConstants.ISLANDORA_CONTENT_MODEL_ATTRIBUTE ).trim();

			if ( collection == null || collection.length() < 1 )
			{
				throw new Exception( "Missing collection in dmdAlliance section" );
			}
			if ( contentModel == null || contentModel.length() < 1 )
			{
				throw new Exception( "Missing contentModel in dmdAlliance section" );
			}

			ingestOptions.setIslandoraCollectionPID( collection );
			ingestOptions.setIslandoraContentModelPID( contentModel ); 
		}
		else 
		{
			throw new Exception( "Missing collection AND contentModel in dmdAlliance section" );
		}
	}

	/**
	 * Filter to find the adr mets:dmd section
	 * 
	 * @see FedoraAppConstant#DMD_ALLIANCE_ID
	 * 
	 * @author chet.rebman
	 *
	 */
	static class IslandoraElementFilter implements Filter {

		public boolean matches( Object testObj ) {
			if ( Element.class.isAssignableFrom( testObj.getClass() ) ) {
				Element element = (Element) testObj;
				if ( element.getName().compareToIgnoreCase( FedoraAppConstants.ISLANDORA_ELEMENT_NAME  ) == 0 ) {
					return true;
				}
			}
			return false;
		}
	} // adrDmdSectionFilter

	/**
	 * parse the command line and save the results in ingestOptions.
	 * The valid command are add and update. As of aug-2012 The add command has type/modifiers are 'normal'
	 * 'replyWithPid' and 'pidInOBJID. The update command has the modifiers 'all', 'meta', 'pco'
	 * 
	 * @param ingestOptions save results here
	 * @param commandLine
	 * @return true on valid command and command type
	 * 
	 * @see BatchIngestOptions#INGEST_COMMAND
	 * @see BatchIngestOptions#UPDATE_COMMAND_TYPE
	 * @see BatchIngestOptions#ADD_COMMAND_TYPE
	 */
	static public boolean parseCommandLine( BatchIngestOptions ingestOptions, String commandLine )
	{
		boolean validCommand = false;
		boolean validType = false;

		// get the command

		int  tempLocation1 =        commandLine.indexOf( "command="+QUOTE );
		if ( tempLocation1 == -1 ){ commandLine.indexOf( "command="+APOST ); }
		
		int tempLocation2        = commandLine.indexOf( QUOTE, tempLocation1+9 );
		if( tempLocation2 == -1 ){ commandLine.indexOf( APOST, tempLocation1+9 ); }
		
		if ( tempLocation1 < 0 || tempLocation2 < 0 )
		{
			return false;
		}
		
		String command = commandLine.substring( tempLocation1+9, tempLocation2 );
		if ( command.toLowerCase().equals( FedoraAppConstants.BATCH_UPDATE_COMMAND ) ){ 	
			ingestOptions.setIngestCommand( BatchIngestOptions.INGEST_COMMAND.UPDATE );
			validCommand = true;

			// get the command modifier (type)

			     tempLocation1 =        commandLine.indexOf( "type="+QUOTE );
			if ( tempLocation1 == -1 ){ commandLine.indexOf( "type="+APOST ); }
			
			    tempLocation2        = commandLine.indexOf( QUOTE, tempLocation1+6 );
			if( tempLocation2 == -1 ){ commandLine.indexOf( APOST, tempLocation1+6 ); }
			
			String type = commandLine.substring( tempLocation1+6, tempLocation2 );
			if ( type.toLowerCase().equals( FedoraAppConstants.BATCH_COMMAND_BATCH_UPDATE_TYPE_ALL.toLowerCase() ) ){ 	
				ingestOptions.setUpdateCommandType( BatchIngestOptions.UPDATE_COMMAND_TYPE.ALL );
				validType = true;
			}
			if ( type.toLowerCase().equals( FedoraAppConstants.BATCH_COMMAND_BATCH_UPDATE_TYPE_META.toLowerCase() ) ){ 	
				ingestOptions.setUpdateCommandType( BatchIngestOptions.UPDATE_COMMAND_TYPE.META );
				validType = true;
			}
			if ( type.toLowerCase().equals( FedoraAppConstants.BATCH_COMMAND_BATCH_UPDATE_TYPE_PCO.toLowerCase() ) ){ 	
				ingestOptions.setUpdateCommandType( BatchIngestOptions.UPDATE_COMMAND_TYPE.PCO );
				validType = true;
			}
		}

		if ( command.toLowerCase().equals("a") )
		{ 	
			ingestOptions.setIngestCommand( BatchIngestOptions.INGEST_COMMAND.ADD );
			validCommand = true;// get the command modifier (type)

			     tempLocation1 =        commandLine.indexOf( "type="+QUOTE );
			if ( tempLocation1 == -1 ){ commandLine.indexOf( "type="+APOST );}
			
		         tempLocation2        = commandLine.indexOf( QUOTE, tempLocation1+6 );
			if ( tempLocation2 == -1 ){ commandLine.indexOf( APOST, tempLocation1+6 ); }
			
			String type = commandLine.substring( tempLocation1+6, tempLocation2 );
			if ( type.toLowerCase().equals( FedoraAppConstants.BATCH_COMMAND_BATCH_ADD_TYPE_NORMAL.toLowerCase() ) ){ 	
				ingestOptions.setAddCommandType( BatchIngestOptions.ADD_COMMAND_TYPE.NORMAL );
				validType = true;
			}
			if ( type.toLowerCase().equals( FedoraAppConstants.BATCH_COMMAND_BATCH_ADD_TYPE_PidInOBJID.toLowerCase() ) ){ 
				ingestOptions.setAddCommandType( BatchIngestOptions.ADD_COMMAND_TYPE.PID_IN_OBJID );
				validType = true;
			}
			if ( type.toLowerCase().equals( FedoraAppConstants.BATCH_COMMAND_BATCH_ADD_TYPE_ReplyWithPid.toLowerCase() ) ){ 
				ingestOptions.setAddCommandType( BatchIngestOptions.ADD_COMMAND_TYPE.REPLY_WITH_PID );
				validType = true;
			}
		}	

		if ( validCommand && validType )	
			return true;
		else
			return false;

	} // parseCommandLine

	/**
	 * This is used by the Ingester for FACTS
	 * 
	 * @param discoveryID
	 * @return true if the string probably contains a vailid discovery id.
	 */
	static public boolean objID_isValid_DU_discoveryID( String discoveryID )
	{
		if ( discoveryID == null )
		{
			return false;
		}

		if ( discoveryID.length() == 0 )
		{
			return false;
		}

		boolean isValid          = false;
		boolean startsWithLetter = true;	
		int     minValidLength   = -1; // set below.

		if ( discoveryID.startsWith( "0" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "1" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "2" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "3" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "4" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "5" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "6" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "7" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "8" )){ startsWithLetter = false; }
		if ( discoveryID.startsWith( "9" )){ startsWithLetter = false; }

		if ( startsWithLetter )
		{
			minValidLength = 2;
		}
		else 
		{
			minValidLength   = 1;
		}

		if ( discoveryID.length() >= minValidLength )
		{
			isValid = true;
		}

		return isValid;
	}
} // MetsBatchFileSplitter