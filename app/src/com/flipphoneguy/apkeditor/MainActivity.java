package com.flipphoneguy.apkeditor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Html;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * Native front-end for the APK Manifest Editor backend
 * ({@link ApiClient#BASE}). Three states — pick, edit, result — mirror the web
 * tool. Files are chosen and saved via the Storage Access Framework; a launch
 * gate requests legacy storage permissions on older Android.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_APK = 1;
    private static final int REQ_PICK_KEYSTORE = 2;
    private static final int REQ_SAVE_APK = 3;
    private static final int RP_STORAGE = 10;

    private static final Pattern PKG_RE =
        Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$");
    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private int appliedMode = -1;

    // shell
    private ScrollView stage;
    private View overlay;
    private ProgressBar ovBar;
    private TextView ovMsg, headerTitle;

    // session
    private String token;
    private String uploadFilename;
    private ManifestModel original, model;
    private ApiClient.BuildResult lastBuild;

    // keystore selection
    private Uri ksUri;
    private String ksName;
    private long ksSize;

    // editor field refs (rebuilt per renderEditor)
    private EditText fPackage, fVersionCode, fVersionName, fLabel, fMinSdk, fTargetSdk;
    private TextView ePackage, eVersionCode, eMinSdk, eTargetSdk, labelNote;
    private TextView hMinSdk, hTargetSdk;
    private UiKit.Section secIdentity, secSdk;
    private RadioButton signUser;
    private EditText ksPass, keyPass, ksAlias;
    private Button ksFileBtn;
    private LinearLayout ksFields;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedMode = ThemeHelper.getMode(this);
        setContentView(R.layout.activity_main);

        stage = findViewById(R.id.stage);
        overlay = findViewById(R.id.overlay);
        ovBar = findViewById(R.id.ov_bar);
        ovMsg = findViewById(R.id.ov_msg);
        headerTitle = findViewById(R.id.header_title);

        findViewById(R.id.btn_info).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, InfoActivity.class));
            }
        });

        if (hasStorageAccess()) showUpload();
        else showGate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedMode != ThemeHelper.getMode(this)) recreate();
    }

    @Override
    public void onBackPressed() {
        // From editor/result, back returns to the picker (and frees the server job).
        if (model != null || lastBuild != null) {
            cancelJob();
            resetSession();
            showUpload();
        } else {
            super.onBackPressed();
        }
    }

    // ── Permission gate ─────────────────────────────────────────────────────

    private boolean hasStorageAccess() {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 33) {
            // No runtime permission applies to arbitrary files; SAF handles it.
            return getPreferences(MODE_PRIVATE).getBoolean("storage_ack", false);
        }
        boolean read = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
        boolean write = sdk > 28 || checkSelfPermission(
            Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return read && write;
    }

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT <= 28) {
            return new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private void showGate() {
        headerTitle.setText(R.string.app_name);
        final boolean modern = Build.VERSION.SDK_INT >= 33;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = UiKit.card(this);
        card.addView(UiKit.text(this, getString(R.string.perm_title), 18,
            R.color.text_primary, true));
        TextView body = UiKit.text(this,
            getString(modern ? R.string.perm_body : R.string.perm_body_legacy),
            14, R.color.text_secondary, false);
        LinearLayout.LayoutParams blp = UiKit.matchW(this, 16);
        blp.topMargin = UiKit.dp(this, 8);
        body.setLayoutParams(blp);
        card.addView(body);

        Button grant = UiKit.primary(this,
            getString(modern ? R.string.perm_continue : R.string.perm_grant));
        grant.setLayoutParams(UiKit.matchW());
        grant.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (modern) {
                    ackStorage();
                    showUpload();
                } else {
                    requestPermissions(neededPerms(), RP_STORAGE);
                }
            }
        });
        card.addView(grant);

        if (!modern
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            // Likely permanently denied — offer the settings route.
            Button settings = UiKit.outline(this, getString(R.string.perm_open_settings));
            LinearLayout.LayoutParams slp = UiKit.matchW();
            slp.topMargin = UiKit.dp(this, 8);
            settings.setLayoutParams(slp);
            settings.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                }
            });
            card.addView(settings);
        }

        TextView skip = UiKit.text(this, getString(R.string.perm_skip), 13,
            R.color.text_secondary, false);
        LinearLayout.LayoutParams klp = UiKit.matchW();
        klp.topMargin = UiKit.dp(this, 14);
        skip.setLayoutParams(klp);
        skip.setGravity(Gravity.CENTER);
        skip.setFocusable(true);
        skip.setClickable(true);
        skip.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ackStorage(); showUpload(); }
        });
        card.addView(skip);

        col.addView(card);
        setStage(col);
    }

    private void ackStorage() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("storage_ack", true).apply();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == RP_STORAGE) {
            if (hasStorageAccess()) showUpload();
            else showGate();   // re-show, now possibly with the settings route
        }
    }

    // ── Upload screen ───────────────────────────────────────────────────────

    private void showUpload() {
        headerTitle.setText(R.string.app_name);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(UiKit.text(this, getString(R.string.upload_title), 22,
            R.color.text_primary, true));
        TextView sub = UiKit.text(this, getString(R.string.upload_subtitle), 14,
            R.color.text_secondary, false);
        LinearLayout.LayoutParams slp = UiKit.matchW(this, 18);
        slp.topMargin = UiKit.dp(this, 6);
        sub.setLayoutParams(slp);
        col.addView(sub);

        Button pick = UiKit.primary(this, getString(R.string.btn_pick_apk));
        pick.setLayoutParams(UiKit.matchW(this, 14));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickApk(); }
        });
        col.addView(pick);

        TextView steps = UiKit.text(this, getString(R.string.upload_steps), 13,
            R.color.text_secondary, true);
        steps.setGravity(Gravity.CENTER);
        steps.setLayoutParams(UiKit.matchW(this, 16));
        col.addView(steps);

        LinearLayout blurb = UiKit.card(this);
        blurb.setLayoutParams(UiKit.matchW());
        blurb.addView(UiKit.text(this, html(R.string.upload_blurb), 13,
            R.color.text_secondary, false));
        col.addView(blurb);

        setStage(col);
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/vnd.android.package-archive",
            "application/octet-stream", "application/zip"});
        startActivityForResult(i, REQ_PICK_APK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_PICK_APK) doUpload(uri);
        else if (req == REQ_PICK_KEYSTORE) onKeystorePicked(uri);
        else if (req == REQ_SAVE_APK) doSave(uri);
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    private void doUpload(final Uri uri) {
        overlay(true, getString(R.string.ov_uploading, 0), 0);
        new Thread(new Runnable() {
            @Override public void run() {
                File temp = null;
                try {
                    String[] ns = queryNameSize(uri);
                    String name = ns[0] != null ? ns[0] : "app.apk";
                    if (!name.toLowerCase().endsWith(".apk")) name += ".apk";
                    long size = ns[1] != null ? Long.parseLong(ns[1]) : -1;
                    if (size < 0) { temp = copyToCache(uri, "upload.apk"); size = temp.length(); }
                    final File ftemp = temp;

                    ApiClient.UploadResult r = ApiClient.upload(
                        new MultipartBody.StreamOpener() {
                            @Override public InputStream open() throws java.io.IOException {
                                return ftemp != null ? new FileInputStream(ftemp)
                                    : getContentResolver().openInputStream(uri);
                            }
                        }, size, name, new MultipartBody.Progress() {
                            @Override public void onProgress(final long done, final long total) {
                                final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (pct >= 100) overlay(true, getString(R.string.ov_reading), -1);
                                        else overlay(true, getString(R.string.ov_uploading, pct), pct);
                                    }
                                });
                            }
                        });

                    token = r.token;
                    uploadFilename = r.filename;
                    original = ManifestModel.fromJson(r.model);
                    model = original.copy();

                    runOnUiThread(new Runnable() {
                        @Override public void run() { overlay(false, null, -1); renderEditor(); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            overlay(false, null, -1);
                            toast(getString(R.string.msg_upload_failed, msg(e)));
                        }
                    });
                } finally {
                    if (temp != null) temp.delete();
                }
            }
        }).start();
    }

    // ── Editor screen ───────────────────────────────────────────────────────

    private void renderEditor() {
        headerTitle.setText(uploadFilename != null ? uploadFilename
            : getString(R.string.app_name));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        if (model.pkg != null) {
            TextView pkg = UiKit.text(this, "Original package: " + model.pkg, 13,
                R.color.text_secondary, false);
            pkg.setLayoutParams(UiKit.matchW(this, 12));
            col.addView(pkg);
        }

        // App identity
        secIdentity = UiKit.section(this, getString(R.string.sec_identity), true);
        secIdentity.container.setLayoutParams(UiKit.matchW(this, 10));
        fPackage = field(secIdentity.body, getString(R.string.lbl_package), model.pkg,
            getString(R.string.tip_package));
        fPackage.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ePackage = errorView(secIdentity.body);
        fVersionCode = field(secIdentity.body, getString(R.string.lbl_version_code),
            model.versionCode, getString(R.string.tip_version_code));
        fVersionCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        eVersionCode = errorView(secIdentity.body);
        fVersionName = field(secIdentity.body, getString(R.string.lbl_version_name),
            model.versionName, getString(R.string.tip_version_name));
        fLabel = field(secIdentity.body, getString(R.string.lbl_label),
            model.labelValue == null ? "" : model.labelValue, getString(R.string.tip_label));
        labelNote = noteView(secIdentity.body);
        if (model.labelEditable) {
            labelNote.setVisibility(View.GONE);
        } else {
            fLabel.setEnabled(false);
            fLabel.setAlpha(0.55f);
            labelNote.setText(model.labelValue != null
                ? "This label points to a resource (" + model.labelValue + ") and can't be changed here."
                : "No editable label on this app.");
        }
        col.addView(secIdentity.container);

        // SDK
        secSdk = UiKit.section(this, getString(R.string.sec_sdk), false);
        secSdk.container.setLayoutParams(UiKit.matchW(this, 10));
        fMinSdk = field(secSdk.body, getString(R.string.lbl_min_sdk), model.minSdkVersion,
            getString(R.string.tip_min_sdk));
        fMinSdk.setInputType(InputType.TYPE_CLASS_NUMBER);
        hMinSdk = noteView(secSdk.body);
        eMinSdk = errorView(secSdk.body);
        fTargetSdk = field(secSdk.body, getString(R.string.lbl_target_sdk), model.targetSdkVersion,
            getString(R.string.tip_target_sdk));
        fTargetSdk.setInputType(InputType.TYPE_CLASS_NUMBER);
        hTargetSdk = noteView(secSdk.body);
        eTargetSdk = errorView(secSdk.body);
        wireSdkHint(fMinSdk, hMinSdk);
        wireSdkHint(fTargetSdk, hTargetSdk);
        col.addView(secSdk.container);

        // Flags
        UiKit.Section secFlags = UiKit.section(this, getString(R.string.sec_flags), false);
        secFlags.container.setLayoutParams(UiKit.matchW(this, 10));
        buildFlags(secFlags.body);
        col.addView(secFlags.container);

        // Permissions
        UiKit.Section secPerms = UiKit.section(this, getString(R.string.sec_perms), false);
        secPerms.container.setLayoutParams(UiKit.matchW(this, 10));
        buildPerms(secPerms);
        col.addView(secPerms.container);

        // Activities
        UiKit.Section secActs = UiKit.section(this, getString(R.string.sec_acts), false);
        secActs.container.setLayoutParams(UiKit.matchW(this, 10));
        buildActs(secActs);
        col.addView(secActs.container);

        // Signing
        UiKit.Section secSign = UiKit.section(this, getString(R.string.sec_signing), false);
        secSign.container.setLayoutParams(UiKit.matchW(this, 10));
        buildSigning(secSign.body);
        col.addView(secSign.container);

        // Action bar
        Button build = UiKit.primary(this, getString(R.string.btn_build));
        build.setLayoutParams(UiKit.matchW(this, 8));
        build.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doBuild(); }
        });
        col.addView(build);

        Button revert = UiKit.outline(this, getString(R.string.btn_revert));
        revert.setLayoutParams(UiKit.matchW(this, 8));
        revert.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { model = original.copy(); renderEditor(); }
        });
        col.addView(revert);

        Button cancel = UiKit.outline(this, getString(R.string.btn_cancel));
        cancel.setLayoutParams(UiKit.matchW());
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelJob(); resetSession(); showUpload(); }
        });
        col.addView(cancel);

        setStage(col);
    }

    /** Label + EditText into a section body; returns the EditText. */
    private EditText field(LinearLayout body, String label, String value) {
        return field(body, label, value, null);
    }

    /** Label line, then an [EditText] [?] row (so d-pad down lands on the input,
     *  and left/right moves between the input and its help button). */
    private EditText field(LinearLayout body, final String label, String value, final String tip) {
        TextView l = UiKit.text(this, label, 13, R.color.text_secondary, true);
        LinearLayout.LayoutParams llp = UiKit.matchW();
        llp.topMargin = UiKit.dp(this, 10);
        l.setLayoutParams(llp);
        body.addView(l);

        EditText e = UiKit.input(this);
        e.setText(value == null ? "" : value);

        if (tip == null) {
            LinearLayout.LayoutParams elp = UiKit.matchW();
            elp.topMargin = UiKit.dp(this, 5);
            e.setLayoutParams(elp);
            body.addView(e);
            return e;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = UiKit.matchW();
        rlp.topMargin = UiKit.dp(this, 5);
        row.setLayoutParams(rlp);
        e.setLayoutParams(new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(e);
        row.addView(helpButton(label, tip));
        body.addView(row);
        return e;
    }

    /** Small "?" button that pops an explanatory dialog (the web tooltips). */
    private Button helpButton(final String title, final String tip) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText("?");
        b.setTextSize(13);
        b.setTextColor(getColor(R.color.btn_outline_text));
        b.setBackground(getDrawable(R.drawable.btn_outline));
        int s = UiKit.dp(this, 32);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.leftMargin = UiKit.dp(this, 8);
        b.setLayoutParams(lp);
        b.setPadding(0, 0, 0, 0);
        b.setFocusable(true);
        b.setContentDescription(getString(R.string.help));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title).setMessage(tip)
                    .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        return b;
    }

    private String flagTip(String k) {
        switch (k) {
            case "debuggable": return getString(R.string.tip_debuggable);
            case "allowBackup": return getString(R.string.tip_allowBackup);
            case "usesCleartextTraffic": return getString(R.string.tip_usesCleartextTraffic);
            case "extractNativeLibs": return getString(R.string.tip_extractNativeLibs);
            case "largeHeap": return getString(R.string.tip_largeHeap);
            default: return null;
        }
    }

    private TextView errorView(LinearLayout body) {
        TextView t = UiKit.text(this, "", 12, R.color.danger, true);
        LinearLayout.LayoutParams lp = UiKit.matchW();
        lp.topMargin = UiKit.dp(this, 4);
        t.setLayoutParams(lp);
        t.setVisibility(View.GONE);
        body.addView(t);
        return t;
    }

    private TextView noteView(LinearLayout body) {
        TextView t = UiKit.text(this, "", 12, R.color.text_secondary, false);
        LinearLayout.LayoutParams lp = UiKit.matchW();
        lp.topMargin = UiKit.dp(this, 4);
        t.setLayoutParams(lp);
        body.addView(t);
        return t;
    }

    private void buildFlags(LinearLayout body) {
        for (final String k : model.flags.keySet()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(UiKit.matchW(this, 2));

            CheckBox cb = UiKit.check(this, k);
            cb.setChecked(Boolean.TRUE.equals(model.flags.get(k)));
            cb.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    model.flags.put(k, on);
                }
            });
            row.addView(cb);

            String tip = flagTip(k);
            if (tip != null) row.addView(helpButton(k, tip));
            body.addView(row);
        }
        TextView note = UiKit.text(this, getString(R.string.flags_note), 12,
            R.color.text_secondary, false);
        LinearLayout.LayoutParams lp = UiKit.matchW();
        lp.topMargin = UiKit.dp(this, 8);
        note.setLayoutParams(lp);
        body.addView(note);
    }

    private void buildPerms(final UiKit.Section sec) {
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutParams(UiKit.matchW());
        sec.body.addView(list);
        renderPerms(sec, list);

        final EditText add = UiKit.input(this);
        add.setHint(R.string.perm_add_hint);
        add.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams alp = UiKit.matchW();
        alp.topMargin = UiKit.dp(this, 8);
        add.setLayoutParams(alp);
        sec.body.addView(add);

        Button addBtn = UiKit.outline(this, "＋ " + getString(R.string.btn_add));
        LinearLayout.LayoutParams blp = UiKit.matchW();
        blp.topMargin = UiKit.dp(this, 6);
        addBtn.setLayoutParams(blp);
        sec.body.addView(addBtn);

        final TextView err = errorView(sec.body);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String val = add.getText().toString().trim();
                if (val.isEmpty()) return;
                if (val.matches(".*\\s.*")) { showErr(err, "Permission names can't contain spaces."); return; }
                if (!val.matches("^[a-zA-Z][\\w.]*\\.[A-Za-z0-9_]+$")) {
                    showErr(err, "That doesn't look like a permission name (e.g. android.permission.CAMERA).");
                    return;
                }
                err.setVisibility(View.GONE);
                if (!model.permissions.contains(val)) {
                    model.permissions.add(val);
                    java.util.Collections.sort(model.permissions);
                    renderPerms(sec, list);
                }
                add.setText("");
            }
        });
    }

    private void renderPerms(final UiKit.Section sec, final LinearLayout list) {
        list.removeAllViews();
        sec.setBadge(String.valueOf(model.permissions.size()));
        for (int i = 0; i < model.permissions.size(); i++) {
            final String p = model.permissions.get(i);
            LinearLayout row = listRow();
            TextView name = UiKit.text(this, p, 12, R.color.text_primary, false);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            name.setSingleLine(true);
            row.addView(name);
            row.addView(removeBtn(new Runnable() {
                @Override public void run() { model.permissions.remove(p); renderPerms(sec, list); }
            }));
            list.addView(row);
        }
    }

    private void buildActs(final UiKit.Section sec) {
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutParams(UiKit.matchW());

        TextView hint = UiKit.text(this, html(R.string.acts_hint), 12,
            R.color.text_secondary, false);
        hint.setBackground(getDrawable(R.drawable.input_bg));
        int p = UiKit.dp(this, 10);
        hint.setPadding(p, p, p, p);
        hint.setLayoutParams(UiKit.matchW(this, 8));
        sec.body.addView(hint);
        sec.body.addView(list);

        TextView note = UiKit.text(this, getString(R.string.acts_note), 12,
            R.color.text_secondary, false);
        LinearLayout.LayoutParams nlp = UiKit.matchW();
        nlp.topMargin = UiKit.dp(this, 8);
        note.setLayoutParams(nlp);
        sec.body.addView(note);

        renderActs(sec, list);
    }

    private void renderActs(final UiKit.Section sec, final LinearLayout list) {
        list.removeAllViews();
        sec.setBadge(String.valueOf(model.activities.size()));
        for (int i = 0; i < model.activities.size(); i++) {
            final ManifestModel.Activity a = model.activities.get(i);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(getDrawable(R.drawable.input_bg));
            int pad = UiKit.dp(this, 10);
            box.setPadding(pad, pad, pad, pad);
            box.setLayoutParams(UiKit.matchW(this, 6));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = UiKit.text(this, a.name, 12, R.color.text_primary, false);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            name.setSingleLine(true);
            top.addView(name);
            final TextView pill = UiKit.text(this, getString(R.string.pill_launcher), 10,
                R.color.success, true);
            pill.setBackground(getDrawable(R.drawable.pill_bg));
            int hp = UiKit.dp(this, 7), vp = UiKit.dp(this, 2);
            pill.setPadding(hp, vp, hp, vp);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.rightMargin = UiKit.dp(this, 6);
            pill.setLayoutParams(plp);
            pill.setVisibility(a.launchable ? View.VISIBLE : View.GONE);
            top.addView(pill);
            top.addView(removeBtn(new Runnable() {
                @Override public void run() { model.activities.remove(a); renderActs(sec, list); }
            }));
            box.addView(top);

            LinearLayout ctrls = new LinearLayout(this);
            ctrls.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams clp = UiKit.matchW();
            clp.topMargin = UiKit.dp(this, 4);
            ctrls.setLayoutParams(clp);

            CheckBox exported = UiKit.check(this, getString(R.string.ck_exported));
            exported.setChecked(a.exported == Boolean.TRUE);
            exported.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    a.exported = on;
                }
            });
            LinearLayout.LayoutParams exlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            exlp.rightMargin = UiKit.dp(this, 16);
            exported.setLayoutParams(exlp);
            ctrls.addView(exported);

            CheckBox launch = UiKit.check(this, getString(R.string.ck_launchable));
            launch.setChecked(a.launchable);
            launch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    a.launchable = on;
                    pill.setVisibility(on ? View.VISIBLE : View.GONE);   // inline; no rebuild
                }
            });
            ctrls.addView(launch);
            box.addView(ctrls);

            list.addView(box);
        }
    }

    private void buildSigning(LinearLayout body) {
        final RadioButton def = new RadioButton(this);
        def.setText(R.string.sign_default);
        def.setTextColor(getColor(R.color.text_primary));
        def.setChecked(true);
        body.addView(def);

        TextView note = UiKit.text(this, getString(R.string.sign_default_note), 12,
            R.color.text_secondary, false);
        LinearLayout.LayoutParams nlp = UiKit.matchW();
        nlp.leftMargin = UiKit.dp(this, 30);
        nlp.bottomMargin = UiKit.dp(this, 6);
        note.setLayoutParams(nlp);
        body.addView(note);

        signUser = new RadioButton(this);
        signUser.setText(R.string.sign_user);
        signUser.setTextColor(getColor(R.color.text_primary));
        body.addView(signUser);

        // The two radios live in one parent here, but wire exclusivity explicitly
        // so it's robust regardless of layout.
        def.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { signUser.setChecked(false); updateKsFields(); }
        });
        signUser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { def.setChecked(false); updateKsFields(); }
        });

        ksFields = new LinearLayout(this);
        ksFields.setOrientation(LinearLayout.VERTICAL);
        ksFields.setLayoutParams(UiKit.matchW());
        ksFields.setVisibility(View.GONE);

        ksFileBtn = UiKit.outline(this, ksName != null ? ksName
            : getString(R.string.btn_pick_keystore));
        LinearLayout.LayoutParams flp = UiKit.matchW();
        flp.topMargin = UiKit.dp(this, 8);
        ksFileBtn.setLayoutParams(flp);
        ksFileBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickKeystore(); }
        });
        ksFields.addView(ksFileBtn);

        ksPass = field(ksFields, getString(R.string.lbl_store_pass), "");
        ksPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyPass = field(ksFields, getString(R.string.lbl_key_pass), "",
            getString(R.string.tip_key_pass));
        keyPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ksAlias = field(ksFields, getString(R.string.lbl_alias), "",
            getString(R.string.tip_alias));

        body.addView(ksFields);
    }

    private void updateKsFields() {
        ksFields.setVisibility(signUser.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void pickKeystore() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK_KEYSTORE);
    }

    private void onKeystorePicked(Uri uri) {
        ksUri = uri;
        String[] ns = queryNameSize(uri);
        ksName = ns[0] != null ? ns[0] : "user.keystore";
        ksSize = ns[1] != null ? Long.parseLong(ns[1]) : -1;
        if (ksFileBtn != null) ksFileBtn.setText(ksName);
    }

    // ── Validation + SDK hint ───────────────────────────────────────────────

    private boolean validate() {
        clearErr(ePackage, fPackage);
        clearErr(eVersionCode, fVersionCode);
        clearErr(eMinSdk, fMinSdk);
        clearErr(eTargetSdk, fTargetSdk);
        EditText first = null;

        String pkg = fPackage.getText().toString().trim();
        if (pkg.isEmpty()) { fieldErr(ePackage, fPackage, "Package name is required."); first = fPackage; }
        else if (pkg.matches(".*\\s.*")) { fieldErr(ePackage, fPackage, "No spaces allowed in a package name."); first = fPackage; }
        else if (!PKG_RE.matcher(pkg).matches()) {
            fieldErr(ePackage, fPackage, "Use a format like com.example.app — letters, digits and underscores only, at least one dot.");
            first = fPackage;
        }

        String vc = fVersionCode.getText().toString().trim();
        if (!vc.isEmpty() && !DIGITS.matcher(vc).matches()) {
            fieldErr(eVersionCode, fVersionCode, "Version code must be a whole number.");
            if (first == null) first = fVersionCode;
        }

        first = checkSdk(fMinSdk, eMinSdk, "Min", first);
        first = checkSdk(fTargetSdk, eTargetSdk, "Target", first);

        if (first != null) { secIdentity.open(); secSdk.open(); first.requestFocus(); return false; }
        return true;
    }

    private EditText checkSdk(EditText f, TextView e, String which, EditText first) {
        String v = f.getText().toString().trim();
        if (v.isEmpty()) return first;
        if (!DIGITS.matcher(v).matches()) {
            fieldErr(e, f, which + " SDK must be a whole number (an API level like 21).");
            return first == null ? f : first;
        }
        int n = Integer.parseInt(v);
        if (n < 1 || n > 40) {
            fieldErr(e, f, "That API level looks out of range (expected 1–40).");
            return first == null ? f : first;
        }
        return first;
    }

    private void wireSdkHint(final EditText e, final TextView hint) {
        hint.setText(sdkHint(e.getText().toString()));
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void afterTextChanged(android.text.Editable s) {
                hint.setText(sdkHint(s.toString()));
            }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });
    }

    private String sdkHint(String v) {
        v = v.trim();
        if (!DIGITS.matcher(v).matches()) return "";
        int n = Integer.parseInt(v);
        String a = androidName(n);
        return a != null ? ("API " + n + " · Android " + a) : ("API " + n);
    }

    private static String androidName(int n) {
        switch (n) {
            case 16: return "4.1 Jelly Bean"; case 19: return "4.4 KitKat";
            case 21: return "5.0 Lollipop"; case 22: return "5.1 Lollipop";
            case 23: return "6.0 Marshmallow"; case 24: return "7.0 Nougat";
            case 25: return "7.1 Nougat"; case 26: return "8.0 Oreo";
            case 27: return "8.1 Oreo"; case 28: return "9 Pie";
            case 29: return "10"; case 30: return "11"; case 31: return "12";
            case 32: return "12L"; case 33: return "13"; case 34: return "14";
            case 35: return "15"; case 36: return "16";
            default: return null;
        }
    }

    // ── Build ───────────────────────────────────────────────────────────────

    private void doBuild() {
        if (!validate()) { toast(getString(R.string.msg_fix_fields)); return; }

        model.pkg = fPackage.getText().toString().trim();
        model.versionCode = fVersionCode.getText().toString().trim();
        model.versionName = fVersionName.getText().toString().trim();
        model.minSdkVersion = fMinSdk.getText().toString().trim();
        model.targetSdkVersion = fTargetSdk.getText().toString().trim();
        if (model.labelEditable) model.labelValue = fLabel.getText().toString();

        final ApiClient.Keystore ks;
        if (signUser != null && signUser.isChecked()) {
            if (ksUri == null) { toast(getString(R.string.msg_pick_keystore)); return; }
            ks = new ApiClient.Keystore();
            final Uri u = ksUri;
            long size = ksSize;
            File kt = null;
            if (size < 0) {
                try { kt = copyToCache(u, "user.keystore"); size = kt.length(); }
                catch (Exception e) { toast(getString(R.string.msg_build_failed, msg(e))); return; }
            }
            final File ktemp = kt;
            ks.opener = new MultipartBody.StreamOpener() {
                @Override public InputStream open() throws java.io.IOException {
                    return ktemp != null ? new FileInputStream(ktemp)
                        : getContentResolver().openInputStream(u);
                }
            };
            ks.size = size;
            ks.filename = ksName;
            ks.storePass = ksPass.getText().toString();
            ks.keyPass = keyPass.getText().toString();
            ks.alias = ksAlias.getText().toString().trim();
        } else {
            ks = null;
        }

        overlay(true, getString(R.string.ov_building), -1);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final JSONObject edits = model.toEdits(original);
                    final ApiClient.BuildResult r = ApiClient.build(token, edits, ks);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { overlay(false, null, -1); showResult(r); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            overlay(false, null, -1);
                            toast(getString(R.string.msg_build_failed, msg(e)));
                        }
                    });
                }
            }
        }).start();
    }

    // ── Result screen ───────────────────────────────────────────────────────

    private void showResult(ApiClient.BuildResult r) {
        lastBuild = r;
        headerTitle.setText(R.string.app_name);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout card = UiKit.card(this);
        card.setLayoutParams(UiKit.matchW());
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        String done = getString(R.string.result_done)
            + (r.elapsed >= 0 ? "  ·  " + r.elapsed + "s" : "");
        card.addView(UiKit.text(this, "✓ " + done, 20, R.color.success, true));

        TextView name = UiKit.text(this, r.filename + "  ·  " + fmtSize(r.size), 13,
            R.color.text_secondary, false);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = UiKit.matchW();
        nlp.topMargin = UiKit.dp(this, 8);
        name.setLayoutParams(nlp);
        card.addView(name);

        TextView ver = UiKit.text(this, getString(R.string.result_verified), 12,
            R.color.success, false);
        ver.setGravity(Gravity.CENTER);
        card.addView(ver);

        if (!r.warnings.isEmpty()) {
            StringBuilder sb = new StringBuilder("<b>" + getString(R.string.warn_heads_up) + "</b>");
            for (String w : r.warnings) sb.append("<br/>• ").append(android.text.TextUtils.htmlEncode(w));
            TextView warn = UiKit.text(this, html(sb.toString()), 12, R.color.text_primary, false);
            warn.setBackground(getDrawable(R.drawable.warn_bg));
            int p = UiKit.dp(this, 10);
            warn.setPadding(p, p, p, p);
            LinearLayout.LayoutParams wlp = UiKit.matchW();
            wlp.topMargin = UiKit.dp(this, 14);
            warn.setLayoutParams(wlp);
            card.addView(warn);
        }

        Button save = UiKit.primary(this, "⬇ " + getString(R.string.btn_save_apk));
        LinearLayout.LayoutParams slp = UiKit.matchW();
        slp.topMargin = UiKit.dp(this, 18);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveApk(); }
        });
        card.addView(save);

        Button another = UiKit.outline(this, getString(R.string.btn_edit_another));
        LinearLayout.LayoutParams alp = UiKit.matchW();
        alp.topMargin = UiKit.dp(this, 8);
        another.setLayoutParams(alp);
        another.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resetSession(); showUpload(); }
        });
        card.addView(another);

        TextView priv = UiKit.text(this, getString(R.string.result_priv), 11,
            R.color.text_secondary, false);
        priv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = UiKit.matchW();
        plp.topMargin = UiKit.dp(this, 12);
        priv.setLayoutParams(plp);
        card.addView(priv);

        col.addView(card);
        setStage(col);
    }

    private void saveApk() {
        if (lastBuild == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_TITLE, lastBuild.filename);
        startActivityForResult(i, REQ_SAVE_APK);
    }

    private void doSave(final Uri dest) {
        final ApiClient.BuildResult r = lastBuild;
        if (r == null) return;
        overlay(true, getString(R.string.ov_saving, 0), 0);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    OutputStream out = getContentResolver().openOutputStream(dest);
                    if (out == null) throw new java.io.IOException("Could not open destination.");
                    try {
                        ApiClient.download(r.downloadPath, out, r.size, new MultipartBody.Progress() {
                            @Override public void onProgress(final long done, final long total) {
                                final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        overlay(true, getString(R.string.ov_saving, pct), pct);
                                    }
                                });
                            }
                        });
                    } finally {
                        out.close();
                    }
                    token = null;   // server deletes the job after a successful download
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            overlay(false, null, -1);
                            toast(getString(R.string.msg_saved, r.filename));
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            overlay(false, null, -1);
                            toast(getString(R.string.msg_save_failed, msg(e)));
                        }
                    });
                }
            }
        }).start();
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    private LinearLayout listRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(getDrawable(R.drawable.input_bg));
        int p = UiKit.dp(this, 8);
        row.setPadding(p, p, p, p);
        row.setLayoutParams(UiKit.matchW(this, 6));
        return row;
    }

    private Button removeBtn(final Runnable onRemove) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText("🗑");
        b.setTextSize(14);
        b.setBackground(getDrawable(R.drawable.btn_outline));
        b.setTextColor(getColor(R.color.btn_outline_text));
        int w = UiKit.dp(this, 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, w);
        lp.leftMargin = UiKit.dp(this, 8);
        b.setLayoutParams(lp);
        b.setFocusable(true);
        b.setContentDescription(getString(R.string.btn_remove));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onRemove.run(); }
        });
        return b;
    }

    private void showErr(TextView e, String msg) { e.setText(msg); e.setVisibility(View.VISIBLE); }

    private void fieldErr(TextView e, EditText f, String msg) {
        showErr(e, msg);
        UiKit.markError(f, true);
    }

    private void clearErr(TextView e, EditText f) {
        if (e != null) e.setVisibility(View.GONE);
        if (f != null) UiKit.markError(f, false);
    }

    private void setStage(View content) {
        stage.scrollTo(0, 0);
        stage.removeAllViews();
        stage.addView(content);
    }

    private void overlay(boolean show, String msg, int pct) {
        overlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (msg != null) ovMsg.setText(msg);
        if (pct < 0) {
            ovBar.setVisibility(View.GONE);
        } else {
            ovBar.setVisibility(View.VISIBLE);
            ovBar.setProgress(pct);
        }
    }

    private void cancelJob() {
        final String t = token;
        token = null;
        if (t != null) new Thread(new Runnable() {
            @Override public void run() { ApiClient.cancel(t); }
        }).start();
    }

    private void resetSession() {
        token = null; original = null; model = null; lastBuild = null;
        ksUri = null; ksName = null; ksSize = 0;
    }

    private String[] queryNameSize(Uri uri) {
        String name = null, size = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = String.valueOf(c.getLong(si));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return new String[]{name, size};
    }

    private File copyToCache(Uri uri, String name) throws java.io.IOException {
        File out = new File(getCacheDir(), name);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("Cannot read the selected file.");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out;
    }

    private CharSequence html(String s) {
        return Build.VERSION.SDK_INT >= 24
            ? Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY) : Html.fromHtml(s);
    }

    private CharSequence html(int resId) { return html(getString(resId)); }

    private static String fmtSize(long b) {
        if (b < 0) return "";
        if (b < 1048576) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
