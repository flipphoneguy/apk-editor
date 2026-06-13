package com.flipphoneguy.apkeditor;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small programmatic view builders so the editor (whose flag/permission/activity
 * lists are dynamic) can be assembled in Java while still reusing the template's
 * card/button drawables and light/dark colors. Everything is focusable and
 * d-pad friendly.
 */
final class UiKit {

    private UiKit() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static LinearLayout.LayoutParams matchW() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchW(Context c, int bottomMarginDp) {
        LinearLayout.LayoutParams lp = matchW();
        lp.bottomMargin = dp(c, bottomMarginDp);
        return lp;
    }

    static TextView text(Context c, CharSequence s, float sizeSp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(c.getColor(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(c.getDrawable(R.drawable.card_bg));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static EditText input(Context c) {
        EditText e = new EditText(c);
        e.setBackground(c.getDrawable(R.drawable.input_bg));
        int px = dp(c, 11), py = dp(c, 9);
        e.setPadding(px, py, px, py);
        e.setTextColor(c.getColor(R.color.text_primary));
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    static void markError(EditText e, boolean error) {
        e.setBackground(e.getContext().getDrawable(
            error ? R.drawable.input_bg_error : R.drawable.input_bg));
    }

    static Button primary(Context c, CharSequence s) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(c.getColor(R.color.text_on_primary));
        b.setBackground(c.getDrawable(R.drawable.btn_filled));
        b.setMinHeight(dp(c, 46));
        b.setFocusable(true);
        return b;
    }

    static Button outline(Context c, CharSequence s) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(15);
        b.setTextColor(c.getColor(R.color.btn_outline_text));
        b.setBackground(c.getDrawable(R.drawable.btn_outline));
        b.setMinHeight(dp(c, 46));
        b.setFocusable(true);
        return b;
    }

    static CheckBox check(Context c, CharSequence s) {
        CheckBox cb = new CheckBox(c);
        cb.setText(s);
        cb.setTextSize(14);
        cb.setTextColor(c.getColor(R.color.text_primary));
        cb.setFocusable(true);
        // d-pad: OK/center toggles (default); left/right are left free for normal
        // focus movement (e.g. between the exported and launchable boxes).
        return cb;
    }

    /** A collapsible card: header row (▸ title + optional badge) toggling a body. */
    static final class Section {
        final LinearLayout container;
        final LinearLayout body;
        private final TextView arrow;
        final TextView badge;

        private Section(Context c, String title, boolean open) {
            container = card(c);

            LinearLayout header = new LinearLayout(c);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setFocusable(true);
            header.setClickable(true);

            arrow = text(c, open ? "▾  " : "▸  ", 13, R.color.text_secondary, false);
            TextView t = text(c, title, 15, R.color.text_primary, true);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            t.setLayoutParams(tlp);

            badge = text(c, "", 12, R.color.accent, true);
            badge.setVisibility(View.GONE);

            header.addView(arrow);
            header.addView(t);
            header.addView(badge);

            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams blp = matchW();
            blp.topMargin = dp(c, 12);
            body.setLayoutParams(blp);
            body.setVisibility(open ? View.VISIBLE : View.GONE);

            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean show = body.getVisibility() != View.VISIBLE;
                    body.setVisibility(show ? View.VISIBLE : View.GONE);
                    arrow.setText(show ? "▾  " : "▸  ");
                }
            });

            container.addView(header);
            container.addView(body);
        }

        void open() {
            body.setVisibility(View.VISIBLE);
            arrow.setText("▾  ");
        }

        void setBadge(CharSequence s) {
            badge.setText(s);
            badge.setVisibility(View.VISIBLE);
        }
    }

    static Section section(Context c, String title, boolean open) {
        return new Section(c, title, open);
    }
}
