/* Türkay Biliyor */
package com.wififilemanager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.StrictMode;
import android.app.Activity;
import android.content.Context;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
public class Filemanager extends Activity {
	private Button BtnStart;
	private TextView Statustxt,Desctxt;
	HttpServer server;
	private int PORT = 1234;
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		if (android.os.Build.VERSION.SDK_INT >= 11) {
			StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
			StrictMode.setThreadPolicy(policy);
			}
		
		Statustxt=(TextView) findViewById(R.id.txt_status);
		Desctxt=(TextView) findViewById(R.id.txt_desc);
		BtnStart=(Button) findViewById(R.id.btn_start);
		BtnStart.setText("Start");		
		
		BtnStart.setOnClickListener(new View.OnClickListener() {					
			@Override
			public void onClick(View arg0) {					
				if(BtnStart.getText().equals("Start"))
					{
					// test functions
					//Utils.getMACAddress("wlan0");
					//Utils.getMACAddress("eth0");
					//Utils.getIPAddress(true); // IPv4
					//Utils.getIPAddress(false); // IPv6 
					
					Statustxt.setText("http://"+getWifiApIpAddress() + ":" + PORT);	
					Desctxt.setText("Enter the adress in your web browser");
					try {
						server = new HttpServer(PORT);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
						
					BtnStart.setText("Stop");	
					}
				else{ 
					server.stop();
					finish();	
			        }
				}
		    });
	}	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.filemanager, menu);
		return true;
	}
	@Override
	protected void onResume() {
		super.onResume();		
		Statustxt.setText("http://"+getWifiApIpAddress() + ":" + PORT);	
		Desctxt.setText("Enter the adress in your web browser");
		try {	
			server = new HttpServer(PORT);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}			
		BtnStart.setText("Stop");				
	}
	public String getWifiApIpAddress() {
	    try {
	    	String wifiipadress="";
	    	NetworkInfo wifiinfo = getNetworkInfo(getBaseContext());
			NetworkInfo mobileinfo = get3gNetworkInfo(getBaseContext());
			if (mobileinfo.getState() == NetworkInfo.State.CONNECTED || mobileinfo.getState() == NetworkInfo.State.CONNECTING) {
				 for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en
			                .hasMoreElements();) {
			            NetworkInterface intf = en.nextElement();	           
			            if (intf.getName().contains("wlan")) {
			                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
			                        .hasMoreElements();) {
			                    InetAddress inetAddress = enumIpAddr.nextElement();
			                    if (!inetAddress.isLoopbackAddress()
			                            && (inetAddress.getAddress().length == 4)) {	
			                    	wifiipadress=inetAddress.getHostAddress();
			                    }
			                }
			            } else
			            	wifiipadress=Utils.getIPAddress(true);
			            	
			        }				
			} else if (wifiinfo.getState() == NetworkInfo.State.CONNECTED || wifiinfo.getState() == NetworkInfo.State.CONNECTING) {
				 for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en
			                .hasMoreElements();) {
			            NetworkInterface intf = en.nextElement();	           
			            if (intf.getName().contains("wlan")) {
			                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr
			                        .hasMoreElements();) {
			                    InetAddress inetAddress = enumIpAddr.nextElement();
			                    if (!inetAddress.isLoopbackAddress()
			                            && (inetAddress.getAddress().length == 4)) {	
			                    	wifiipadress=inetAddress.getHostAddress();
			                    }
			                }
			            } 			            	
			        }								
			}	
	       
	        return wifiipadress; 	     
	    } catch (SocketException ex) {	        
	    }
	    return null;
	}	
    public static NetworkInfo getNetworkInfo(Context context){
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getNetworkInfo(1);
    }
    public static NetworkInfo get3gNetworkInfo(Context context){
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getNetworkInfo(0);
    }  
}
