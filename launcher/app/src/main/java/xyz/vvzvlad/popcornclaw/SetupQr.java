package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * A small home-screen card with a QR that encodes the box's control panel URL
 * {@code http://<box LAN IPv4>:<ControlServer.PORT>/?pin=<PIN>}. The owner scans it with a phone to open the
 * settings panel already logged in; the PIN ({@link Pin}) is also written under the QR for typing it in by
 * hand. The IP is read at render time ({@link #onAttachedToWindow()} and every time the
 * window becomes visible), so a DHCP change is picked up; with no IPv4 the card hides itself.
 */
public final class SetupQr extends LinearLayout {
    private static final String TAG = "PopcornClawQr";
    private static final String CAPTION = "Scan to configure";

    private final ImageView image;
    private final TextView pinText;
    private final int qrPx;
    private String shownUrl;

    public SetupQr(Context c) {
        super(c);
        float density = getResources().getDisplayMetrics().density;
        // QR bitmap resolution (and the ImageView's pixel size): compact (~180..240 px) so the card stays a
        // small corner element; a short URL still scans fine from a phone held near the TV.
        qrPx = Math.max(180, Math.min(240, Math.round(110 * density)));

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(12 * density);
        setPadding(pad, pad, pad, pad);
        // Subtle dark card so the Russian caption stays legible over any wallpaper.
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xCC000000);
        card.setCornerRadius(12 * density);
        setBackground(card);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(image, new LayoutParams(qrPx, qrPx));

        TextView caption = new TextView(c);
        caption.setText(CAPTION);
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setTextColor(0xFFE8E8E8);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.setMaxWidth(qrPx);
        LayoutParams cl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Math.round(8 * density);
        addView(caption, cl);

        // The panel's PIN, for typing it in when the QR is not used (a PC browser, an MCP client).
        pinText = new TextView(c);
        pinText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinText.setTextColor(0xFFE8E8E8);
        pinText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pinText.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams pl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = Math.round(2 * density);
        addView(pinText, pl);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        render();
    }

    /** The window became visible (home shown again): re-read the IP in case DHCP moved it. */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            render();
        }
    }

    /** Reads the current IP, rebuilds the QR and shows (or hides) the card. Cheap enough to run on show. */
    private void render() {
        String ip = currentIp();
        if (ip == null) {
            shownUrl = null;
            setVisibility(GONE);
            return;
        }
        String pin = Pin.get(getContext());
        String url = "http://" + ip + ":" + ControlServer.PORT + "/?pin=" + pin;
        if (!url.equals(shownUrl)) {
            Bitmap qr = encode(url, qrPx);
            if (qr == null) {
                shownUrl = null;
                setVisibility(GONE);
                return;
            }
            image.setImageBitmap(qr);
            pinText.setText("PIN " + pin);
            shownUrl = url;
        }
        setVisibility(VISIBLE);
    }

    /**
     * The box's physical-LAN IPv4, or null. Interface-agnostic and VPN-safe: enumerates the live
     * interfaces and skips loopback, point-to-point (WireGuard/VPN tun) and virtual ones, so the QR
     * carries the home-LAN address and never a VPN tunnel's. Interface-name
     * preference eth > wlan > en picks Ethernet over Wi-Fi when the box has both; any site-local IPv4
     * is the last resort.
     */
    private static String currentIp() {
        // First site-local IPv4 seen on an interface whose name starts with each preferred prefix.
        String[] prefer = {"eth", "wlan", "en"};
        String[] best = new String[prefer.length];
        String any = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (skip(ni)) {
                    continue;
                }
                String name = ni.getName() != null ? ni.getName().toLowerCase(Locale.ROOT) : "";
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) {
                        continue;
                    }
                    String s = a.getHostAddress();
                    if (any == null) {
                        any = s;
                    }
                    for (int i = 0; i < prefer.length; i++) {
                        if (best[i] == null && name.startsWith(prefer[i])) {
                            best[i] = s;
                        }
                    }
                    break;   // one IPv4 per interface is enough
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "interface scan failed", e);
        }
        for (String b : best) {
            if (b != null) {
                return b;
            }
        }
        return any;
    }

    /** Physical, up interfaces only: drops loopback, point-to-point (WireGuard/VPN tun) and virtual ones. */
    private static boolean skip(NetworkInterface ni) {
        try {
            if (!ni.isUp() || ni.isLoopback() || ni.isPointToPoint() || ni.isVirtual()) {
                return true;
            }
        } catch (Exception e) {
            return true;   // can't tell → do not trust it
        }
        String name = ni.getName() != null ? ni.getName().toLowerCase(Locale.ROOT) : "";
        // Belt-and-suspenders by name, in case a tunnel/virtual interface misreports its flags.
        return name.startsWith("wg") || name.startsWith("tun") || name.startsWith("ppp")
                || name.startsWith("docker") || name.startsWith("rmnet");
    }

    /** Black modules on a white background, white quiet-zone border included, as an ARGB_8888 bitmap. */
    private static Bitmap encode(String url, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 4);   // ISO/IEC 18004 quiet zone, in modules
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[row + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (WriterException e) {
            Log.w(TAG, "qr encode failed", e);
            return null;
        }
    }
}
