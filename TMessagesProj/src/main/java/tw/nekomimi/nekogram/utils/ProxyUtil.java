package tw.nekomimi.nekogram.utils;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class ProxyUtil {
    private static final Pattern IPV6_PATTERN = Pattern.compile("^((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*::((?:[0-9A-Fa-f]{1,4}))?((?::[0-9A-Fa-f]{1,4}))*|((?:[0-9A-Fa-f]{1,4}))((?::[0-9A-Fa-f]{1,4})){7}$");

    private static class ParseResult {
        final ArrayList<SharedConfig.ProxyInfo> proxies;
        final boolean error;

        ParseResult(ArrayList<SharedConfig.ProxyInfo> proxies, boolean error) {
            this.proxies = proxies;
            this.error = error;
        }
    }

    private static void showToast(CharSequence text) {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show());
    }

    private static void showImportedDialog(Activity ctx, List<SharedConfig.ProxyInfo> proxies) {
        if (ctx == null) {
            return;
        }
        StringBuilder message = new StringBuilder(LocaleController.getString(R.string.ImportedProxies));
        message.append("\n\n");
        for (int i = 0; i < proxies.size(); i++) {
            if (i > 0) {
                message.append("\n");
            }
            message.append(proxies.get(i).address);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.show();
    }

    public static void importFromClipboard(Activity ctx) {
        if (ctx == null) {
            return;
        }
        ClipboardManager clipboardManager = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = null;
        if (clipboardManager != null && clipboardManager.getPrimaryClip() != null && clipboardManager.getPrimaryClip().getItemCount() > 0) {
            CharSequence clipText = clipboardManager.getPrimaryClip().getItemAt(0).coerceToText(ApplicationLoader.applicationContext);
            if (clipText != null) {
                text = clipText.toString();
            }
        }

        ParseResult result = parseProxyText(text);
        if (result.proxies.isEmpty()) {
            if (!result.error) {
                showToast(LocaleController.getString(R.string.BrokenLink));
            }
            return;
        } else if (!result.error) {
            showImportedDialog(ctx, result.proxies);
        }

        for (SharedConfig.ProxyInfo info : result.proxies) {
            SharedConfig.addProxy(info);
        }

        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged));
    }

    public static void importFromUrl(Activity ctx, String url, boolean saveSubscription) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        String normalizedUrl = normalizeSubscriptionUrl(url);
        String subscriptionName = getSubscriptionTitle(normalizedUrl);
        if (saveSubscription) {
            addSubscription(normalizedUrl);
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: import url=" + normalizedUrl);
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder buffer = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        buffer.append(line).append('\n');
                    }
                    String text = buffer.toString();
                    ParseResult result = parseProxyText(text);
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("proxy_sub: import fetched bytes=" + text.length() + " proxies=" + result.proxies.size() + " error=" + result.error);
                    }
                    if (result.proxies.isEmpty()) {
                        if (!result.error) {
                            AndroidUtilities.runOnUIThread(() -> showToast(LocaleController.getString(R.string.BrokenLink)));
                        }
                        return;
                    }
                    for (SharedConfig.ProxyInfo info : result.proxies) {
                        if (saveSubscription) {
                            info.subscriptionName = subscriptionName;
                        }
                        SharedConfig.addProxy(info, saveSubscription);
                    }
                    AndroidUtilities.runOnUIThread(() -> {
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                        if (!result.error) {
                            showToast(LocaleController.getString(R.string.ProxySubscriptionAdded));
                        }
                    });
                } finally {
                    conn.disconnect();
                }
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> showToast(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        });
    }

    public static void refreshSubscriptions(Activity ctx) {
        List<String> urls = getSubscriptions();
        if (urls.isEmpty()) {
            showToast(LocaleController.getString(R.string.ProxySubscriptionEmpty));
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: refresh count=" + urls.size());
        }
        Utilities.globalQueue.postRunnable(() -> {
            final boolean[] any = new boolean[]{false};
            for (String url : urls) {
                String normalizedUrl = normalizeSubscriptionUrl(url);
                String subscriptionName = getSubscriptionTitle(normalizedUrl);
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder buffer = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            buffer.append(line).append('\n');
                        }
                        String text = buffer.toString();
                        ParseResult result = parseProxyText(text);
                        for (SharedConfig.ProxyInfo info : result.proxies) {
                            info.subscriptionName = subscriptionName;
                            SharedConfig.addProxy(info, true);
                        }
                        if (!result.proxies.isEmpty()) {
                            any[0] = true;
                        }
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("proxy_sub: refresh url=" + normalizedUrl + " bytes=" + text.length() + " proxies=" + result.proxies.size());
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (any[0]) {
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    showToast(LocaleController.getString(R.string.ProxySubscriptionUpdated));
                } else {
                    showToast(LocaleController.getString(R.string.BrokenLink));
                }
            });
        });
    }

    private static ParseResult parseProxyText(String text) {
        ArrayList<SharedConfig.ProxyInfo> proxies = new ArrayList<>();
        final boolean[] error = new boolean[]{false};

        java.util.function.Consumer<String> handleLine = (line) -> {
            if (TextUtils.isEmpty(line)) {
                return;
            }
            if (line.startsWith("tg://proxy") ||
                    line.startsWith("tg://socks") ||
                    line.startsWith("https://t.me/proxy") ||
                    line.startsWith("https://t.me/socks") ||
                    line.startsWith("vless://")) {
                try {
                    proxies.add(SharedConfig.ProxyInfo.fromUrl(line));
                } catch (Throwable e) {
                    error[0] = true;
                    AndroidUtilities.runOnUIThread(() -> showToast(LocaleController.getString(R.string.BrokenLink) + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
                }
            }
        };

        if (text != null) {
            String trimmed = text.trim();
            if (!trimmed.isEmpty()) {
                String[] lines = trimmed.split("\n");
                for (String line : lines) {
                    String[] parts = line.split(" ");
                    for (String part : parts) {
                        handleLine.accept(part);
                    }
                }
            }
        }

        if (proxies.isEmpty() && !error[0] && !TextUtils.isEmpty(text)) {
            try {
                String decoded = new String(Base64.decode(text, Base64.NO_PADDING));
                String trimmed = decoded.trim();
                if (!trimmed.isEmpty()) {
                    String[] lines = trimmed.split("\n");
                    for (String line : lines) {
                        String[] parts = line.split(" ");
                        for (String part : parts) {
                            handleLine.accept(part);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("proxy_sub: parse result proxies=" + proxies.size() + " error=" + error[0]);
        }
        return new ParseResult(proxies, error[0]);
    }

    private static List<String> getSubscriptions() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        String value = prefs.getString("proxy_subscriptions", "");
        if (value == null) {
            return new ArrayList<>();
        }
        String[] lines = value.split("\n");
        Set<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(normalizeSubscriptionUrl(trimmed));
            }
        }
        return new ArrayList<>(result);
    }

    private static void addSubscription(String url) {
        String normalizedUrl = normalizeSubscriptionUrl(url);
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        List<String> current = getSubscriptions();
        if (!current.contains(normalizedUrl)) {
            current.add(normalizedUrl);
            prefs.edit().putString("proxy_subscriptions", TextUtils.join("\n", current)).apply();
        }
    }

    private static String normalizeSubscriptionUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String trimmed = url.trim();
        if (!trimmed.contains("://")) {
            trimmed = "https://" + trimmed;
        }
        return trimmed;
    }

    private static String getSubscriptionTitle(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        String normalized = normalizeSubscriptionUrl(url);
        Uri uri = Uri.parse(normalized);
        String host = uri != null ? uri.getHost() : null;
        if (TextUtils.isEmpty(host)) {
            return normalized;
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host;
    }

    public static boolean isIpv6Address(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String addr = value;
        if (addr.startsWith("[") && addr.contains("]")) {
            int end = addr.lastIndexOf("]");
            addr = addr.substring(1, end);
        }
        return IPV6_PATTERN.matcher(addr).matches();
    }
}
