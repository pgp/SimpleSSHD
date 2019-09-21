package org.galexander.sshd;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

public class DocActivity extends Activity
{
	public static final String url =
			"http://www.galexander.org/software/simplesshd";
	private WebView wv = null;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.doc);
		wv = (WebView)findViewById(R.id.docview);
	}

	public void onResume() {
		super.onResume();
		wv.loadUrl(url);
	}

	public void onPause() {
		super.onPause();
	}
}
