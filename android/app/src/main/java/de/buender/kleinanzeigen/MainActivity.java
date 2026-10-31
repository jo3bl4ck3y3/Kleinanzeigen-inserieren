package de.buender.kleinanzeigen;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Zeigt die Web-Oberfläche (assets/index.html) und stellt Scanner, Zwischenablage und Teilen bereit. */
public class MainActivity extends Activity {

    private static final String START = "https://appassets.androidplatform.net/assets/index.html";
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FOTOS = 1;

    private WebView web;
    private ValueCallback<Uri[]> fotoRueckruf;
    /** Ausgewählte Fotos, gefunden über „Name|Größe“ bzw. „n:Name“ */
    private final Map<String, Uri> fotoUris = new HashMap<>();

    @Override
    protected void onCreate(Bundle zustand) {
        super.onCreate(zustand);
        web = new WebView(this);
        setContentView(web);

        WebViewAssetLoader lader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new Bruecke(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return lader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                oeffneExtern(u);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> rueckruf, FileChooserParams p) {
                if (fotoRueckruf != null) fotoRueckruf.onReceiveValue(null);
                fotoRueckruf = rueckruf;
                boolean mehrere = p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
                Intent i;
                if (Build.VERSION.SDK_INT >= 33) {
                    i = new Intent(MediaStore.ACTION_PICK_IMAGES);
                    if (mehrere) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 20);
                } else {
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, mehrere);
                }
                try {
                    startActivityForResult(i, REQ_FOTOS);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fotoRueckruf = null;
                    return false;
                }
            }
        });

        if (zustand != null) web.restoreState(zustand);
        else web.loadUrl(START);
    }

    @Override
    protected void onSaveInstanceState(Bundle zustand) {
        super.onSaveInstanceState(zustand);
        web.saveState(zustand);
    }

    @Override
    protected void onActivityResult(int req, int ergebnis, Intent daten) {
        super.onActivityResult(req, ergebnis, daten);
        if (req != REQ_FOTOS || fotoRueckruf == null) return;
        List<Uri> liste = new ArrayList<>();
        if (ergebnis == RESULT_OK && daten != null) {
            ClipData clip = daten.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) liste.add(clip.getItemAt(k).getUri());
            } else if (daten.getData() != null) {
                liste.add(daten.getData());
            }
        }
        for (Uri u : liste) merke(u);
        fotoRueckruf.onReceiveValue(liste.isEmpty() ? null : liste.toArray(new Uri[0]));
        fotoRueckruf = null;
    }

    private void merke(Uri u) {
        try (Cursor c = getContentResolver().query(u,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                long groesse = c.isNull(1) ? -1 : c.getLong(1);
                fotoUris.put(name + "|" + groesse, u);
                fotoUris.put("n:" + name, u);
            }
        } catch (Exception ignoriert) {
        }
    }

    private void oeffneExtern(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Keine App zum Öffnen gefunden", Toast.LENGTH_SHORT).show();
        }
    }

    private void antwort(String status, String wert) {
        String js = "window.nativerScan && window.nativerScan(" + JSONObject.quote(status) + ","
                + JSONObject.quote(wert == null ? "" : wert) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void teile(ArrayList<Uri> quellen, String titel) {
        try {
            File ordner = new File(getCacheDir(), "teilen");
            File[] alt = ordner.listFiles();
            if (alt != null) for (File f : alt) f.delete();
            ordner.mkdirs();
            ArrayList<Uri> ziele = new ArrayList<>();
            ClipData clip = null;
            int nr = 1;
            for (Uri q : quellen) {
                String mime = getContentResolver().getType(q);
                String endung = mime != null && mime.contains("png") ? ".png"
                        : mime != null && mime.contains("webp") ? ".webp" : ".jpg";
                File ziel = new File(ordner, String.format("Foto_%02d%s", nr++, endung));
                try (InputStream in = getContentResolver().openInputStream(q);
                     OutputStream out = new FileOutputStream(ziel)) {
                    byte[] puffer = new byte[65536];
                    int n;
                    while ((n = in.read(puffer)) > 0) out.write(puffer, 0, n);
                }
                Uri z = FileProvider.getUriForFile(this, getPackageName() + ".dateien", ziel);
                ziele.add(z);
                if (clip == null) clip = ClipData.newRawUri(titel, z);
                else clip.addItem(new ClipData.Item(z));
            }
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("image/*");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ziele);
            i.setClipData(clip);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent auswahl = Intent.createChooser(i, "Fotos teilen");
            runOnUiThread(() -> startActivity(auswahl));
        } catch (Exception e) {
            runOnUiThread(() -> Toast.makeText(this, "Fotos konnten nicht geteilt werden", Toast.LENGTH_LONG).show());
        }
    }

    /** Wird im JavaScript als window.AndroidApp angeboten. */
    private class Bruecke {

        @JavascriptInterface
        public void scanBarcode() {
            runOnUiThread(() -> {
                GmsBarcodeScannerOptions optionen = new GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_UPC_A, Barcode.FORMAT_EAN_8)
                        .enableAutoZoom()
                        .build();
                GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(MainActivity.this, optionen);
                scanner.startScan()
                        .addOnSuccessListener(b -> antwort("ok", b.getRawValue()))
                        .addOnCanceledListener(() -> antwort("abbruch", ""))
                        .addOnFailureListener(e -> antwort("fehler", e.getMessage()));
            });
        }

        @JavascriptInterface
        public boolean copy(String text, String bezeichnung) {
            try {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText(bezeichnung, text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String shareImages(String json, String titel) {
            try {
                JSONArray a = new JSONArray(json);
                ArrayList<Uri> quellen = new ArrayList<>();
                for (int k = 0; k < a.length(); k++) {
                    JSONObject o = a.getJSONObject(k);
                    String name = o.optString("name");
                    Uri u = fotoUris.get(name + "|" + o.optLong("size", -1));
                    if (u == null) u = fotoUris.get("n:" + name);
                    if (u == null) return "fehlt";
                    quellen.add(u);
                }
                if (quellen.isEmpty()) return "leer";
                new Thread(() -> teile(quellen, titel == null ? "" : titel)).start();
                return "ok";
            } catch (Exception e) {
                return "fehler";
            }
        }
    }
}
