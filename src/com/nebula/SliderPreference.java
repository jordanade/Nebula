package com.nebula;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.DialogPreference;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * A slider preference that works on API 23 (the platform SeekBarPreference is
 * API 26+). Stores an integer in [min, max] stepped by {@code sliderStep};
 * the displayed value is {@code stored * sliderScale} with an optional suffix
 * (e.g. scale 0.001 shows 45 -> "0.045", scale 1 + "%" shows 75 -> "75%").
 *
 * Presented as a dialog with a SeekBar, which is d-pad adjustable on TV
 * (left/right) once focused.
 */
public class SliderPreference extends DialogPreference {

    private final int min;
    private final int max;
    private final int step;
    private final float scale;
    private final String suffix;

    private int value;
    private SeekBar seekBar;
    private TextView valueLabel;

    public SliderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SliderPreference);
        min   = a.getInt(R.styleable.SliderPreference_sliderMin, 0);
        max   = a.getInt(R.styleable.SliderPreference_sliderMax, 100);
        step  = Math.max(1, a.getInt(R.styleable.SliderPreference_sliderStep, 1));
        scale = a.getFloat(R.styleable.SliderPreference_sliderScale, 1f);
        String sfx = a.getString(R.styleable.SliderPreference_sliderSuffix);
        suffix = (sfx == null) ? "" : sfx;
        a.recycle();

        // DialogPreference shows no buttons unless these are set.
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
    }

    private String format(int stored) {
        float v = stored * scale;
        String num = (scale == 1f) ? Integer.toString(Math.round(v))
                                   : String.format(java.util.Locale.US, "%.3f", v);
        return num + suffix;
    }

    @Override
    protected View onCreateDialogView() {
        Context ctx = getContext();
        int pad = Math.round(ctx.getResources().getDisplayMetrics().density * 24);

        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);

        valueLabel = new TextView(ctx);
        valueLabel.setGravity(Gravity.CENTER);
        valueLabel.setTextSize(22f);
        ll.addView(valueLabel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(ctx);
        seekBar.setMax((max - min) / step);
        seekBar.setProgress((clamp(value) - min) / step);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                valueLabel.setText(format(min + progress * step));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        ll.addView(seekBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        valueLabel.setText(format(clamp(value)));
        seekBar.requestFocus();
        return ll;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult && seekBar != null) {
            int v = min + seekBar.getProgress() * step;
            if (callChangeListener(v)) {
                value = v;
                persistInt(value);
                setSummary(format(value));
            }
        }
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getInt(index, (min + max) / 2);
    }

    @Override
    protected void onSetInitialValue(boolean restorePersisted, Object defaultValue) {
        int def = (defaultValue instanceof Integer) ? (Integer) defaultValue : (min + max) / 2;
        value = clamp(restorePersisted ? getPersistedInt(def) : def);
        if (!restorePersisted) persistInt(value);
        setSummary(format(value));
    }

    private int clamp(int v) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
