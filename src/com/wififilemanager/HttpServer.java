package com.wififilemanager;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Vector;
import android.util.Log;


public class HttpServer {	
	
	public Response serve( String uri, String method, Properties header, Properties parms, Properties files )
	{
		//System.out.println( method + " '" + uri + "' " );

		Enumeration e = header.propertyNames();
		while ( e.hasMoreElements())
		{
			String value = (String)e.nextElement();
			System.out.println( "  HDR: '" + value + "' = '" +
								header.getProperty( value ) + "'" );
		}
		e = parms.propertyNames();
		while ( e.hasMoreElements())
		{
			String value = (String)e.nextElement();
			System.out.println( "  PRM: '" + value + "' = '" +
								parms.getProperty( value ) + "'" );
		}

		return serveFile( uri, header, new File("."), true );
	}


	/**
	 * HTTP response.
	 * Return one of these from serve().
	 */
	public class Response
	{
		/**
		 * Default constructor: response = HTTP_OK, data = mime = 'null'
		 */
		public Response()
		{
			this.status = HTTP_OK;
		}

		/**
		 * Basic constructor.
		 */
		public Response( String status, String mimeType, InputStream data )
		{
			this.status = status;
			this.mimeType = mimeType;
			this.data = data;
		}

		/**
		 * Convenience method that makes an InputStream out of
		 * given text.
		 */
		public Response( String status, String mimeType, String txt )
		{
			this.status = status;
			this.mimeType = mimeType;
			this.data = new ByteArrayInputStream( txt.getBytes());
		}

		/**
		 * Adds given line to the header.
		 */
		public void addHeader( String name, String value )
		{
			header.put( name, value );
		}

		/**
		 * HTTP status code after processing, e.g. "200 OK", HTTP_OK
		 */
		public String status;

		/**
		 * MIME type of content, e.g. "text/html"
		 */
		public String mimeType;

		/**
		 * Data of the response, may be null.
		 */
		public InputStream data;

		/**
		 * Headers for the HTTP response. Use addHeader()
		 * to add lines.
		 */
		public Properties header = new Properties();
	}

	/**
	 * Some HTTP response status codes
	 */
	public static final String
	HTTP_OK = "200 OK",
	HTTP_REDIRECT = "301 Moved Permanently",
	HTTP_FORBIDDEN = "403 Forbidden",
	HTTP_NOTFOUND = "404 Not Found",
	HTTP_BADREQUEST = "400 Bad Request",
	HTTP_INTERNALERROR = "500 Internal Server Error",
	HTTP_NOTIMPLEMENTED = "501 Not Implemented",
	HTTP_PARTIALCONTENT = "206 Partial Content",
	HTTP_RANGE_NOT_SATISFIABLE = "416 Requested Range Not Satisfiable";	

	/**
	 * Common mime types for dynamic content
	 */
	public static final String
	MIME_PLAINTEXT = "text/plain",
	MIME_HTML = "text/html",
	MIME_DEFAULT_BINARY = "application/octet-stream";

	// ==================================================
	// Socket & server code
	// ==================================================

	/**
	 * Starts a HTTP server to given port.<p>
	 * Throws an IOException if the socket is already in use
	 */
	public HttpServer( int port ) throws IOException
	{
		myTcpPort = port;
		runThread = true;
		final ServerSocket ss = new ServerSocket( myTcpPort );
		//ss.setSoTimeout(1000);
		Thread t = new Thread( new Runnable()
		{
			public void run()
			{
				try
				{
					while( runThread )
						new HTTPSession( ss.accept());
				}
				catch ( IOException ioe )
				{Log.i("LOG_pawn", "http" );}
			}
		});
		t.setDaemon( true );
		t.start();
	}

	private boolean runThread;
	public void stop() {
		this.runThread = false;
	}
	private String folder=null,status=null;
	private class HTTPSession implements Runnable
	{
		public HTTPSession( Socket s )
		{
			mySocket = s;

			InetAddress localhost;
			try {
				localhost = InetAddress.getByName(Utils.getIPAddress(true));
			}
			catch (Exception e) {
				localhost = null;
			}
			//if (s.getInetAddress().equals(localhost)) {
			if (localhost != null) {
				Thread t = new Thread( this );
				t.setDaemon( true );
				t.start();
		    }
			else {
				try
				{
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: Invalid ip.");
				}
				catch ( Throwable t ) {}
			}
		}
		public void run()
		{
			try
			{
				InputStream is = mySocket.getInputStream();
				if ( is == null) return;
				//Lets read the first 8192 bytes. 
				//The full header should fit in here. 
				//Apache's default header limit is 8KB.
				int bufsize = 8192;
				byte[] buf = new byte[bufsize];
				int rlen = is.read(buf, 0, bufsize);
				if (rlen <= 0)
				return;

				//We create a BufferedReader for parsing the header.
				ByteArrayInputStream hbis = new ByteArrayInputStream(buf, 0, rlen);
				BufferedReader hin = new BufferedReader( new InputStreamReader( hbis ));
				Properties pre = new Properties();
				Properties parms = new Properties();
				Properties header = new Properties();
				Properties files = new Properties();
				//We decode the header into parms and header java properties
				decodeHeader(hin, pre, parms, header);
				String method = pre.getProperty("method");
				String uri = pre.getProperty("uri");


				long size = 0x7FFFFFFFFFFFFFFFl;
				String contentLength = header.getProperty("content-length");
				if (contentLength != null)
				{
					try { size = Integer.parseInt(contentLength); }
					catch (NumberFormatException ex) {}
				}
				
				//We are looking for the byte separating header from body. 
				//It must be the last byte of the first two sequential new lines.
				int splitbyte = 0;
				boolean sbfound = false;
				while (splitbyte < rlen)
				{
					if (buf[splitbyte] == '\r' && buf[++splitbyte] == '\n' && buf[++splitbyte] == '\r' && buf[++splitbyte] == '\n') {
						sbfound = true;	
						break;
					}
					splitbyte++;
				}
				splitbyte++;

				//We write the part of body already read to ByteArrayOutputStream f
				ByteArrayOutputStream f = new ByteArrayOutputStream();
				f.write(buf, splitbyte, rlen-splitbyte);
				
				//While firefox sends on the first read all the data fitting our buffer, chrome and opera sends only the headers even if there is data for the body. So we do some magic here to find out whether we have already consumed part of body, if we have reached the end of the data to be sent or  we should expect the first byte of the body at the next read.
				if (splitbyte < rlen)
					size -= rlen - splitbyte +1;
				else if (!sbfound || size == 0x7FFFFFFFFFFFFFFFl)
					size = 0;

				//We now read all the body and write it to f
				buf = new byte[512];
				while ( rlen >= 0 && size > 0 )
				{
					rlen = is.read(buf, 0, 512);
					size -= rlen;
					f.write(buf, 0, rlen);
					
				}
				//We get the raw body as a byte []
				byte [] fbuf = f.toByteArray();
				//We also create a BufferedReader for easily reading it as string.
				ByteArrayInputStream bin = new ByteArrayInputStream(fbuf);
				BufferedReader in = new BufferedReader( new InputStreamReader(bin));


				// If the method is POST, there may be parameters
				// in data section, too, read it:
				if ( method.equalsIgnoreCase( "POST" ))
				{
					String contentType = "";
                                	String contentTypeHeader = header.getProperty("content-type");
                                	StringTokenizer st = new StringTokenizer( contentTypeHeader , "; " );
                                	if ( st.hasMoreTokens()) {
                                		contentType = st.nextToken();
                                	}

					if (contentType.equalsIgnoreCase("multipart/form-data")){
						//Content type is  multipart/form-data
						if ( !st.hasMoreTokens())
                                                        sendError( HTTP_BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but boundary missing. Usage: GET /example/file.html" );
                                                String boundaryExp = st.nextToken();
                                                st = new StringTokenizer( boundaryExp , "=" );
                                                if (st.countTokens() != 2)
                                                        sendError( HTTP_BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but boundary syntax error. Usage: GET /example/file.html" );
                                                st.nextToken();
                                                String boundary = st.nextToken();

                                                decodeMultipartData(boundary, fbuf, in, parms, files,uri);
						
					}
					else {
						//content type is application/x-www-form-urlencoded	
						String postLine = "";
						char pbuf[] = new char[512];
						int read = in.read(pbuf);
						while ( read >= 0 && !postLine.endsWith("\r\n") )
						{
							postLine += String.valueOf(pbuf, 0, read);
							if ( read > 0 )
								read = in.read(pbuf);
						}
						postLine = postLine.trim();
						decodeParms( postLine, parms );

						System.out.println(postLine);
						parms.list(System.err);
					}
				}

				// Ok, now do the serve()
				Response r = serve( uri, method, header, parms, files );
				if ( r == null )
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: Serve() returned a null response." );
				else
					sendResponse( r.status, r.mimeType, r.header, r.data );

				in.close();
			}
			catch ( IOException ioe )
			{
				try
				{
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
				}
				catch ( Throwable t ) {}
			}
			catch ( InterruptedException ie )
			{
				try
				{
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: InterruptedException: " + ie.getMessage());
				}
				catch ( Throwable t ) {}
			}
		}		
		private  void decodeHeader(BufferedReader in, Properties pre, Properties parms, Properties header)
				throws InterruptedException
			{
				try {
					// Read the request line
					String inLine = in.readLine();
					if (inLine == null) return;
					StringTokenizer st = new StringTokenizer( inLine );
					if ( !st.hasMoreTokens())
						sendError( HTTP_BADREQUEST, "BAD REQUEST: Syntax error. Usage: GET /example/file.html" );

					String method = st.nextToken();
					pre.put("method", method);

					if ( !st.hasMoreTokens())
						sendError( HTTP_BADREQUEST, "BAD REQUEST: Missing URI. Usage: GET /example/file.html" );

					String uri = st.nextToken();
					if(uri.contains("?delete=Delete") || uri.contains("?renamefile="))
	                {                                	
	                	String data = uri;	                	
	                	if(uri.contains("?delete=Delete")){
		                	data = data.substring(data.indexOf("?delete=Delete") + "?delete=Delete".length(), data.length());                                	
		                	int folderend=uri.indexOf("?delete=Delete");
		                	folder=uri.substring(0, folderend);  
		                	if(uri.contains("&"))
		                    {
			                    String[] items = data.split("&");	                	
			                    for (String item : items)
			                    {
			                    	if(item.contains("chkfolder="))
			                        {
			                    		item=item.replace("chkfolder=", "");
			                    		File file = new File(folder+item);
			    	                    deleteDirectory(file);	    	                      
			                        }else if(item.contains("chkfile="))
			                        {
			                        item=item.replace("chkfile=", "");
				                    	File file = new File(folder+item);
				                    	file.delete();
			                        }
			                    }  
		                    }             
	                	}else if(uri.contains("?renamefile=")){
	                		data = data.substring(data.indexOf("?renamefile=") + "?renamefile=".length(), data.length());                                	
		                	int folderend=uri.indexOf("?renamefile=");
		                	folder=uri.substring(0, folderend); 
		                	boolean getnameok=false;
		                	String newname=null;
		                	if(uri.contains("&"))
		                    {		                		
			                    String[] items = data.split("&");	                	
			                    for (String item : items)
			                    {	
			                    	if(!getnameok)
			                    	{
			                    	newname=item;	
			                    	getnameok=true;
			                    	}
			                    	if(item.contains("chkfolder=") && itemcount(data,"chkfolder=")<2)
			                        {
			                    		item=item.replace("chkfolder=", "");
			                    		File myDir = new File(folder);				   			   
			         				    File from = new File (myDir, item);
			         				    File to = new File (myDir, newname);
			                        	from.renameTo(to);			                    		
			                    		          
			                        }if(item.contains("chkfile=")&& itemcount(data,"chkfile=")<2)
			                        {
			                        	item=item.replace("chkfile=", "");				                        	
			                        	File myDir = new File(folder);				   			   
			         				    File from = new File (myDir, item);
			         				    File to = new File (myDir, newname);
			                        	from.renameTo(to);
			                        }
			                    }  
		                    }             
	                	}	                		
	                	     	
	                	uri=folder;                  	
	                } else if(uri.contains("createFolder="))
		                { 
		                	String data = uri;
		                	String folder=null;
		                	data = data.substring(data.indexOf("?createFolder=") + "?createFolder=".length(), data.length());                                	
		                	int folderend=uri.indexOf("?createFolder=");
		                	folder=uri.substring(0, folderend);	   
		                	createDirectory(new File(folder+getValidFileName(data)));	
		                	uri=folder; 	                	
		                } 
					// Decode parameters from the URI
					int qmi = uri.indexOf( '?' );
					if ( qmi >= 0 )
					{
						decodeParms( uri.substring( qmi+1 ), parms );
						uri = decodePercent( uri.substring( 0, qmi ));
					}
					else uri = decodePercent(uri);

					// If there's another token, it's protocol version,
					// followed by HTTP headers. Ignore version but parse headers.
					// NOTE: this now forces header names lowercase since they are
					// case insensitive and vary by client.
					if ( st.hasMoreTokens())
					{
						String line = in.readLine();
						while ( line != null && line.trim().length() > 0 )
						{
							int p = line.indexOf( ':' );
							if ( p >= 0 )
								header.put( line.substring(0,p).trim().toLowerCase(), line.substring(p+1).trim());
							line = in.readLine();
						}
					}

					pre.put("uri", uri);
				}
				catch ( IOException ioe )
				{
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
				}
			}
		private int itemcount(String data,String findstring)
		{
			int lastIndex = 0;
    		int count =0;
    		while(lastIndex != -1){

    		       lastIndex = data.indexOf(findstring,lastIndex);

    		       if( lastIndex != -1){
    		             count ++;
    		             lastIndex+=findstring.length();
    		      }
    		}
			return count;	
		}
		private void decodeMultipartData(String boundary, byte[] fbuf, BufferedReader in, Properties parms, Properties files,String Uri)
				throws InterruptedException
			{
				try
				{
					int[] bpositions = getBoundaryPositions(fbuf,boundary.getBytes());
					int boundarycount = 1;
					String mpline = in.readLine();
					while ( mpline != null )
					{
						if (mpline.indexOf(boundary) == -1)
							sendError( HTTP_BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but next chunk does not start with boundary. Usage: GET /example/file.html" );
						boundarycount++;
						Properties item = new Properties();
						mpline = in.readLine();
						while (mpline != null && mpline.trim().length() > 0)
						{
							int p = mpline.indexOf( ':' );
							if (p != -1)
								item.put( mpline.substring(0,p).trim().toLowerCase(), mpline.substring(p+1).trim());
							mpline = in.readLine();
						}
						if (mpline != null)
						{
							String contentDisposition = item.getProperty("content-disposition");
							if (contentDisposition == null)
							{
								sendError( HTTP_BADREQUEST, "BAD REQUEST: Content type is multipart/form-data but no content-disposition info found. Usage: GET /example/file.html" );
							}
							StringTokenizer st = new StringTokenizer( contentDisposition , "; " );
							Properties disposition = new Properties();
							while ( st.hasMoreTokens())
							{
								String token = st.nextToken();
								int p = token.indexOf( '=' );
								if (p!=-1)
									disposition.put( token.substring(0,p).trim().toLowerCase(), token.substring(p+1).trim());
							}
							String pname = disposition.getProperty("name");
							pname = pname.substring(1,pname.length()-1);

							String value = "";
							if (item.getProperty("content-type") == null) {
								while (mpline != null && mpline.indexOf(boundary) == -1)
								{
									mpline = in.readLine();
									if ( mpline != null)
									{
										int d = mpline.indexOf(boundary);
										if (d == -1)
											value+=mpline;
										else
											value+=mpline.substring(0,d-2);
									}
								}
							}
							else
							{
								if (boundarycount> bpositions.length)
									sendError( HTTP_INTERNALERROR, "Error processing request" );
								int offset = stripMultipartHeaders(fbuf, bpositions[boundarycount-2]);
								value = disposition.getProperty("filename");
								value = value.substring(1,value.length()-1);	
								String path =saveFile(fbuf, offset, bpositions[boundarycount-1]-offset-4,value,Uri);								
								files.put(pname, path);													
								do {
									mpline = in.readLine();
								} while (mpline != null && mpline.indexOf(boundary) == -1);								
							}
							parms.put(pname, value);
						}
					}
				}
				catch ( IOException ioe )
				{
					sendError( HTTP_INTERNALERROR, "SERVER INTERNAL ERROR: IOException: " + ioe.getMessage());
				}
			}
		public int[] getBoundaryPositions(byte[] b, byte[] boundary)
		{
			int matchcount = 0;
			int matchbyte = -1;
			Vector<Integer> matchbytes = new Vector<Integer>();
			for (int i=0; i<b.length; i++)
			{
				if (b[i] == boundary[matchcount])
				{
					if (matchcount == 0)
						matchbyte = i;
					matchcount++;
					if (matchcount==boundary.length)
					{
						matchbytes.addElement(new Integer(matchbyte));
						matchcount = 0;
						matchbyte = -1;
					}
				}
				else
				{
					i -= matchcount;
					matchcount = 0;
					matchbyte = -1;
				}
			}
			int[] ret = new int[matchbytes.size()];
			for (int i=0; i < ret.length; i++)
			{
				ret[i] = ((Integer)matchbytes.elementAt(i)).intValue();
			}
			return ret;
		}
		
		private String saveFile(byte[] b, int offset, int len,String filename,String Uri)
		{
			String path = "";
			if (len > 0)
			{				
				try {	
					filename.replaceAll("[^A-Za-z0-9]", "");
				    File myDir = new File(Uri);				   			   
				    File file = new File (myDir, filename);
				    if (file.exists ()) file.delete (); 
				    try {
				           FileOutputStream fstream = new FileOutputStream(file);
				           fstream.write(b, offset, len);
				           fstream.flush();
				           fstream.close();
				    } catch (Exception e) {
				           e.printStackTrace();
				    }
					path = file.getAbsolutePath();					
				} catch (Exception e) { // Catch exception if any
					System.err.println("Error: " + e.getMessage());
				}
			}
			return path;
		}
		private int stripMultipartHeaders(byte[] b, int offset)
		{
			int i = 0;
			for (i=offset; i<b.length; i++)
			{
				if (b[i] == '\r' && b[++i] == '\n' && b[++i] == '\r' && b[++i] == '\n')
					break;
			}
			return i+1;
		}
	public String getValidFileName(String fileName) {
		fileName = fileName.replaceAll("[^a-zA-Z0-9.-]", "_");
	    return fileName;
	}
	public void createDirectory(File path) {       
        if( !path.exists() ) {
        	path.mkdir();
        }       
    }
	
	public boolean deleteDirectory(File path) {
        // TODO Auto-generated method stub
        if( path.exists() ) {
            File[] files = path.listFiles();
            for(int i=0; i<files.length; i++) {
            if(files[i].isDirectory()) {
                    deleteDirectory(files[i]);
                }
                else {
                    files[i].delete();
                }
            }
        }
        return(path.delete());
    }
		private String decodePercent( String str ) throws InterruptedException
		{
			try
			{
				StringBuffer sb = new StringBuffer();
				for( int i=0; i<str.length(); i++ )
				{
					char c = str.charAt( i );
					switch ( c )
					{
					case '+':
						sb.append( ' ' );
						break;
					case '%':
						sb.append((char)Integer.parseInt( str.substring(i+1,i+3), 16 ));
						i += 2;
						break;
					default:
						sb.append( c );
					break;
					}
				}
				return new String( sb.toString().getBytes());
			}
			catch( Exception e )
			{
				sendError( HTTP_BADREQUEST, "BAD REQUEST: Bad percent-encoding." );
				return null;
			}
		}

		/**
		 * Decodes parameters in percent-encoded URI-format
		 * ( e.g. "name=Jack%20Daniels&pass=Single%20Malt" ) and
		 * adds them to given Properties.
		 */
		private void decodeParms( String parms, Properties p )
		throws InterruptedException
		{
			if ( parms == null )
				return;


			StringTokenizer st = new StringTokenizer( parms, "&" );
			while ( st.hasMoreTokens())
			{
				String e = st.nextToken();
				int sep = e.indexOf( '=' );
				if ( sep >= 0 )
					p.put( decodePercent( e.substring( 0, sep )).trim(),
							decodePercent( e.substring( sep+1 )));
			}
		}

		/**
		 * Returns an error message as a HTTP response and
		 * throws InterruptedException to stop furhter request processing.
		 */
		private void sendError( String status, String msg ) throws InterruptedException
		{
			sendResponse( status, MIME_PLAINTEXT, null, new ByteArrayInputStream( msg.getBytes()));
			throw new InterruptedException();
		}

		/**
		 * Sends given response to the socket.
		 */
		private void sendResponse( String status, String mime, Properties header, InputStream data )
		{
			try
			{
				if ( status == null )
					throw new Error( "sendResponse(): Status can't be null." );

				OutputStream out = mySocket.getOutputStream();
				PrintWriter pw = new PrintWriter( out );
				pw.print("HTTP/1.0 " + status + " \r\n");

				if ( mime != null )
					pw.print("Content-Type: " + mime + "\r\n");

				if ( header == null || header.getProperty( "date" ) == null )
					pw.print( "Date: " + gmtFrmt.format( new Date()) + "\r\n");

				if ( header != null )
				{
					Enumeration<?> e = header.keys();
					while ( e.hasMoreElements())
					{
						String key = (String)e.nextElement();
						String value = header.getProperty( key );
						pw.print( key + ": " + value + "\r\n");
					}
				}

				pw.print("\r\n");
				pw.flush();

				if ( data != null )
				{
					byte[] buff = new byte[2048];
					while (true)
					{
						int read = data.read( buff, 0, 2048 );
						if (read <= 0)
							break;
						out.write( buff, 0, read );
					}
				}
				out.flush();
				out.close();
				if ( data != null )
					data.close();
			}
			catch( IOException ioe )
			{
				// Couldn't write? No can do.
				try { mySocket.close(); } catch( Throwable t ) {}
			}
		}

		private Socket mySocket;
	};

	/**
	 * URL-encodes everything between "/"-characters.
	 * Encodes spaces as '%20' instead of '+'.
	 */
	private String encodeUri( String uri )
	{
		String newUri = "";
		StringTokenizer st = new StringTokenizer( uri, "/ ", true );
		while ( st.hasMoreTokens())
		{
			String tok = st.nextToken();
			if ( tok.equals( "/" ))
				newUri += "/";
			else if ( tok.equals( " " ))
				newUri += "%20";
			else
			{
				newUri += URLEncoder.encode( tok );
				// For Java 1.4 you'll want to use this instead:
					// try { newUri += URLEncoder.encode( tok, "UTF-8" ); } catch ( UnsupportedEncodingException uee )
			}
		}
		return newUri;
	}

	private int myTcpPort;
	File myFileDir;

	// ==================================================
	// File server code
	// ==================================================

	/**
	 * Serves file from homeDir and its' subdirectories (only).
	 * Uses only URI, ignores all headers and HTTP parameters.
	 */
	public Response serveFile( String uri, Properties header, File homeDir,
			boolean allowDirectoryListing )
	{
		Response res = null;

		// Make sure we won't die of an exception later
		if ( !homeDir.isDirectory())
			res = new Response( HTTP_INTERNALERROR, MIME_PLAINTEXT,
				"INTERNAL ERRROR: serveFile(): given homeDir is not a directory." );

		if ( res == null )
		{
			// Remove URL arguments
			uri = uri.trim().replace( File.separatorChar, '/' );
			if ( uri.indexOf( '?' ) >= 0 )
				uri = uri.substring(0, uri.indexOf( '?' ));

			// Prohibit getting out of current directory
			if ( uri.startsWith( ".." ) || uri.endsWith( ".." ) || uri.indexOf( "../" ) >= 0 )
				res = new Response( HTTP_FORBIDDEN, MIME_PLAINTEXT,
					"FORBIDDEN: Won't serve ../ for security reasons." );
		}

		File f = new File( homeDir, uri );
		if ( res == null && !f.exists())
			res = new Response( HTTP_NOTFOUND, MIME_PLAINTEXT,
				"Error 404, file not found." );

		// List the directory, if necessary
		if ( res == null && f.isDirectory())
		{
			// Browsers get confused without '/' after the
			// directory, send a redirect.
			if ( !uri.endsWith( "/" ))
			{
				uri += "/";
				res = new Response( HTTP_REDIRECT, MIME_HTML,
					"<html><body>Redirected: <a href=\"" + uri + "\">" +
					uri + "</a></body></html>");
				res.addHeader( "Location", uri );
			}

			if ( res == null )
			{
				// First try index.html and index.htm
				if ( new File( f, "index.html" ).exists())
					f = new File( homeDir, uri + "/index.html" );
				else if ( new File( f, "index.htm" ).exists())
					f = new File( homeDir, uri + "/index.htm" );
				// No index file, list the directory if it is readable
				else if ( allowDirectoryListing && f.canRead() )
				{
					Hashtable<String, Hashtable<String, String>> styles = new Hashtable<String, Hashtable<String, String>>();
					Hashtable<String, String> style;
					
					style = new Hashtable<String, String>();
					style.put("background", "#150517");
					style.put("color", "#ffffff");
					style.put("face", "verdana");
					style.put("padding-top", "10px");
					styles.put(".localhttpd", style);
					
					String header_block = "<head><style>";
					for (Enumeration<String> e = styles.keys(); e.hasMoreElements();) {
					String key = (String) e.nextElement();
					Hashtable<?, ?> item = (Hashtable<?, ?>) styles.get(key);
					header_block += key + "{\n";
					for (Enumeration<?> e2 = item.keys(); e2.hasMoreElements();) {
					     String skey = (String) e2.nextElement();
					     header_block += skey + ":" + item.get(skey) + ";\n";
					}
					header_block += key + "}\n";
					}
					header_block += "</style>" +
									"<SCRIPT language=Javascript>"+
								       "function isEnglishKey(event)"+
								       "{"+
								       "var ew = event.which;"+
								       "if(ew == 32)return true;"+
								       "if(ew == 46)return true;"+
								       "if(48 <= ew && ew <= 57)return true;"+
								       "if(65 <= ew && ew <= 90)return true;"+
								       "if(97 <= ew && ew <= 122)return true;"+
								       "return false;"+
								       "}"+						       
						    		"</SCRIPT>"+
						    		"</head>";
					String[] files = f.list();
					String msg = "<html>" + header_block +                         		
				       		"<body class=\"localhttpd\">"+ 
				       	    "<div><div style=\"margin-left:20px\">"+
				       		"<table bgcolor=\"#41627E\" align=\"left\" border=\"0\">"+ 
				       		"<form action=\".\" method=\"post\" enctype=\"multipart/form-data\">"+  
				       		"<tr bgcolor=\"#A52A2A\" align=\"left\">"+				       		
				            "<th><input type=\"file\" name=\"sentFile\" size=\"40\" />" +
				            "<input type=\"submit\" name=\"submit\" value=\"Upload File\" /></th>"+
				            "</form>" + 
				            "<form method=\"get\">"+				           
				            "<th colspan=\"4\"><input type=\"text\" name=\"createFolder\" onkeypress=\"return isEnglishKey(event)\" />"+    
				            "<input type=\"submit\" value=\"New Folder\" /></th>" +
				            "</form>"+					          
					        "</tr>";		
				       if ( uri.length() > 1 )
				       {
				               String u = uri.substring( 0, uri.length()-1 );
				               int slash = u.lastIndexOf( '/' );
				               if ( slash >= 0 && slash  < u.length())
				            	   msg += "<tr bgcolor=\"#008000\" align=\"left\">";	
				                   msg += "<td colspan=\"4\"><b><a href=\"" + uri.substring(0, slash+1) + "\">";
				                   msg +="<font size=\"4\" face=\"verdana\" color=\"#FFFF00\">Back</font></a>&nbsp;&nbsp;&nbsp;"; 		                
				                   msg +="<font size=\"4\" face=\"verdana\" color=\"white\">";
				                   msg +="Directory: </font>";
				                   msg +="<font size=\"4\" face=\"verdana\" color=\"white\">";
				                   msg += uri + "</font></b></td>"; 
				                   msg +="</tr>";
				       }
				       msg +="<form method=\"get\"><tr bgcolor=\"#FFF000\" align=\"left\">";				      
				       msg +="<td colspan=\"4\"><font size=\"3\" face=\"verdana\">";
				       msg +="<input type=\"submit\" name=\"delete\" value=\"Delete Items\"/></font>" ;	
				       msg +="<input type=\"text\" name=\"renamefile\" onkeypress=\"return isEnglishKey(event)\" />";   
				       msg +="<input type=\"submit\" value=\"Rename\" />" ;
				       msg +="</td></tr>";					      
				       msg +="<tr bgcolor=\"#A52A2A\" align=\"left\">";
				       msg +="<th><font size=\"3\" face=\"verdana\" color=\"white\">Name</th>";		
				       msg +="<th><font size=\"3\" face=\"verdana\" color=\"white\">Size</th>";	
				       msg +="<th><font size=\"3\" face=\"verdana\" color=\"white\">Date</th>";	
				       msg +="<th><input type=\"checkbox\" name=\"chkallfile\"/></th>";
	                   msg +="</tr>";	                  
				       msg +=listfolders(files, f,uri);
				       msg +=listfiles(files, f,uri);		
				       msg +="</form>"; 
				       msg += "</b>";
				       msg +="<tr bgcolor=\"#A52A2A\" align=\"left\">";
				       msg +="<th colspan=\"4\"><font size=\"2\" face=\"verdana\" color=\"white\">";					     
	                   msg +="Wifi File Manager for android V1.0 by Turkay Biliyor</th></tr>";	                  
				       msg +="</table></div></div>";				        
					res = new Response( HTTP_OK, MIME_HTML, msg );
				}
				else
				{
					res = new Response( HTTP_FORBIDDEN, MIME_PLAINTEXT,
						"FORBIDDEN: No directory listing." );
				}
			}
		}

		try
		{
			if ( res == null )
			{
				// Get MIME type from file name extension, if possible
				String mime = null;
				int dot = f.getCanonicalPath().lastIndexOf( '.' );
				if ( dot >= 0 )
					mime = (String)theMimeTypes.get( f.getCanonicalPath().substring( dot + 1 ).toLowerCase());
				if ( mime == null )
					mime = MIME_DEFAULT_BINARY;

				// Calculate etag
				String etag = Integer.toHexString((f.getAbsolutePath() + f.lastModified() + "" + f.length()).hashCode());

				// Support (simple) skipping:
				long startFrom = 0;
				long endAt = -1;
				String range = header.getProperty( "range" );
				if ( range != null )
				{
					if ( range.startsWith( "bytes=" ))
					{
						range = range.substring( "bytes=".length());
						int minus = range.indexOf( '-' );
						try {
							if ( minus > 0 )
							{
								startFrom = Long.parseLong( range.substring( 0, minus ));
								endAt = Long.parseLong( range.substring( minus+1 ));
							}
						}
						catch ( NumberFormatException nfe ) {}
					}
				}

				// Change return code and add Content-Range header when skipping is requested
				long fileLen = f.length();
				if (range != null && startFrom >= 0)
				{
					if ( startFrom >= fileLen)
					{
						res = new Response( HTTP_RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "" );
						res.addHeader( "Content-Range", "bytes 0-0/" + fileLen);
						res.addHeader( "ETag", etag);
					}
					else
					{
						if ( endAt < 0 )
							endAt = fileLen-1;
						long newLen = endAt - startFrom + 1;
						if ( newLen < 0 ) newLen = 0;

						final long dataLen = newLen;
						FileInputStream fis = new FileInputStream( f ) {
							public int available() throws IOException { return (int)dataLen; }
						};
						fis.skip( startFrom );

						res = new Response( HTTP_PARTIALCONTENT, mime, fis );
						res.addHeader( "Content-Length", "" + dataLen);
						res.addHeader( "Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
						res.addHeader( "ETag", etag);
					}
				}
				else
				{
					res = new Response( HTTP_OK, mime, new FileInputStream( f ));
					res.addHeader( "Content-Length", "" + fileLen);
					res.addHeader( "ETag", etag);
				}
			}
		}
		catch( IOException ioe )
		{
			res = new Response( HTTP_FORBIDDEN, MIME_PLAINTEXT, "FORBIDDEN: Reading file failed." );
		}

		res.addHeader( "Accept-Ranges", "bytes"); // Announce that the file server accepts partial content requestes
		return res;
	}
	private String listfolders(String[] files, File f,String uri)
	{     String msg = "";
		  for ( int i=0; i<files.length; ++i )
	      {
	    	  File curFile = new File( f, files[i] );
	    	
	                  boolean dir = curFile.isDirectory();  
	                  // Show file size
	                  String extra = "";
	                  if ( curFile.isFile())
	                  {
	                          extra = "target='_blank'";
	                  }	             
	                  if ( dir )
	                  {                	 
	                	  Date lm =new Date(curFile.lastModified());	
	                	  String filedate = new SimpleDateFormat("dd-MM-yyyy'_'HH:mm:ss").format(lm);	
	                           msg +="<b>";				                           
			                   msg +="<tr align=\"left\">";				                  
			                   msg +="<td align=\"left\">";
			                   msg +="<a " + extra + " href=\"" + encodeUri( uri + files[i] ) + "\">"  + 
			                		   "<font size=\"3\" face=\"verdana\" color=\"yellow\">"+
			                             files[i] + "<font></a></td><td align=\"right\">";		
			                   msg +="<td align=\"left\"><font size=2 face=\"verdana\" color=\"#FAF8CC\">";
	                           msg +=filedate;
	                           msg += "</font></td>";
			                   msg +="<td><input type=\"checkbox\" name=\"chkfolder\" value=" + files[i] + " /></td> ";
			                   msg +="</tr>";
			                   msg += "</b>";		                    
	                  }                   
	          }
		
		  return msg;
	}
	private String listfiles(String[] files, File f,String uri)
	{ String msg = "";
		  for ( int i=0; i<files.length; ++i )
	      {
	    	  File curFile = new File( f, files[i] );
	                  boolean dir = curFile.isDirectory();  
	                  // Show file size
	                  String extra = "";
	                  if ( curFile.isFile())
	                  {
	                          extra = "target='_blank'";
	                  }	             
	                  if ( !dir )
	                  {                        
			                  if ( curFile.isFile())
			                  {				                	 
			                	  Date lm =new Date(curFile.lastModified());	
			                	  String filedate = new SimpleDateFormat("dd-MM-yyyy'_'HH:mm:ss").format(lm);										                	  
				                 
			                	  msg +="<tr align=\"left\">";				                  
				                   msg +="<td align=\"left\">";
				                   msg +="<a " + extra + " href=\"" + encodeUri( uri + files[i] ) + "\">"  + 
				                		   "<font size=\"2\" face=\"verdana\" color=\"white\">"+
				                             files[i] + "<font></a></td><td align=\"left\">";		
			                          long len = curFile.length();
			                          msg += "<font size=2 face=\"verdana\" color=\"#FAF8CC\">";
			                          if ( len < 1024 )
			                                  msg += curFile.length() + " bytes";
			                          else if ( len < 1024 * 1024 )
			                                  msg += curFile.length()/1024 + "." + (curFile.length()%1024/10%100) + " KB";
			                          else
			                                  msg += curFile.length()/(1024*1024) + "." + curFile.length()%(1024*1024)/10%100 + " MB";
			
			                          msg += "</font></td>";
			                          msg +="<td align=\"left\"><font size=2 face=\"verdana\" color=\"#FAF8CC\">";
			                          msg +=filedate;
			                          msg += "</font></td>";
			                  } 
			                  
			                  msg +="<td><input type=\"checkbox\" name=\"chkfile\" value=" + files[i] + " /></td> ";
			                  msg +="</tr>";			                 
	                  }                
	          }
		   
		  return msg;
	}

	private String SybExt(String filename) {
		// Returns the correct Syabas Html extension
		String extension= null;
		int dot = filename.lastIndexOf( '.' );
		if ( dot >= 0 ){
			extension = (String)theNMTTypes.get(filename.substring(dot + 1).toLowerCase());
		}
		if (extension == null){
			extension="";
		}
		return extension;
	}

	/**
	 * Hashtable mapping (String)FILENAME_EXTENSION -> (String)MIME_TYPE
	 */
	private static Hashtable<String, String> theMimeTypes = new Hashtable<String, String>();
	static
	{
		StringTokenizer st = new StringTokenizer(
				"htm            text/html "+
				"html           text/html "+
				"txt            text/plain "+
				"asc            text/plain "+
				"gif            image/gif "+
				"jpg            image/jpeg "+
				"jpeg           image/jpeg "+
				"png            image/png "+
				"mp3            audio/mpeg "+
				"m3u            audio/mpeg-url " +
				"pdf            application/pdf "+
				"doc            application/msword "+
				"ogg            application/x-ogg "+
				"avi            video/x-msvideo "+
				"zip            application/octet-stream "+
				"exe            application/octet-stream "+
		"class          application/octet-stream " );
		while ( st.hasMoreTokens())
			theMimeTypes.put( st.nextToken(), st.nextToken());
	}

	/**
	 * Johan addition to support SYABAS HTML extensions 
	 * Hashtable mapping (String)FILENAME_EXTENSION -> (String)SYABAS_EXTENSION
	 */
	private static Hashtable<String, String> theNMTTypes = new Hashtable<String, String>();
	static
	{		
		StringTokenizer st = new StringTokenizer(
				"css		text/css "+
				"htm		text/html "+
				"html		text/html "+
				"xml		text/xml "+
				"txt		text/plain "+
				"asc		text/plain "+
				"gif		image/gif "+
				"jpg		image/jpeg "+
				"jpeg		image/jpeg "+
				"png		image/png "+
				"mp3		audio/mpeg "+
				"m3u		audio/mpeg-url " +
				"ogg		audio/ogg " +
				"mp4		video/mp4 " +
				"mpg		video/mpg " +
				"ogv		video/ogg " +
				"mkv		video/mkv " +
				"avi		video/avi " +
				"flv		video/x-flv " +
				"mov		video/quicktime " +
				"swf		application/x-shockwave-flash " +
				"js			application/javascript "+
				"pdf		application/pdf "+
				"doc		application/msword "+
				"ogg		application/x-ogg "+
				"zip		application/octet-stream "+
				"rar		application/octet-stream "+
				"exe		application/octet-stream "+
				"class		application/octet-stream " );
			while ( st.hasMoreTokens())
				theNMTTypes.put( st.nextToken(), st.nextToken());
	}
	
	/**
	 * GMT date formatter
	 */
	private static java.text.SimpleDateFormat gmtFrmt;
	static
	{
		gmtFrmt = new java.text.SimpleDateFormat( "E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
		gmtFrmt.setTimeZone(TimeZone.getTimeZone("GMT"));
	}

	/**
	 * The distribution licence
	 */
	private static final String LICENCE =
		"Copyright (C) 2001,2005-2008 by Jarno Elonen <elonen@iki.fi>\n"+
		"Copyright (C) 2009 by Johan Wieslander <johan@wieslander.eu>\n"+
		"\n"+
		"Redistribution and use in source and binary forms, with or without\n"+
		"modification, are permitted provided that the following conditions\n"+
		"are met:\n"+
		"\n"+
		"Redistributions of source code must retain the above copyright notice,\n"+
		"this list of conditions and the following disclaimer. Redistributions in\n"+
		"binary form must reproduce the above copyright notice, this list of\n"+
		"conditions and the following disclaimer in the documentation and/or other\n"+
		"materials provided with the distribution. The name of the author may not\n"+
		"be used to endorse or promote products derived from this software without\n"+
		"specific prior written permission. \n"+
		" \n"+
		"THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR\n"+
		"IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES\n"+
		"OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.\n"+
		"IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,\n"+
		"INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT\n"+
		"NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,\n"+
		"DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY\n"+
		"THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT\n"+
		"(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE\n"+
		"OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.";

}