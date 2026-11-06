package com.cinemax.nexus.ui;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.cinemax.nexus.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
/** Shared visual language for useful, scrollable tool panels. */
public final class StudioSheet {
    private StudioSheet() {
    }
    public static int dp(Context c,int value) {
        return (int)(value*c.getResources().getDisplayMetrics().density+.5f);
    }
    public static LinearLayout column(Context c) {
        LinearLayout box=new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(c,20);
        box.setPadding(pad,pad,pad,pad);
        box.setBackgroundColor(c.getColor(R.color.nexus_black));
        return box;
    }
    public static TextView text(Context c,String text,int size) {
        TextView view=new TextView(c);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(c.getColor(R.color.nexus_text));
        view.setPadding(0,dp(c,8),0,dp(c,8));
        return view;
    }
    public static void heading(LinearLayout box,String title,String description) {
        TextView name=text(box.getContext(),title,24);
        name.setTypeface(null,Typeface.BOLD);
        box.addView(name);
        TextView detail=text(box.getContext(),description,13);
        detail.setTextColor(box.getContext().getColor(R.color.nexus_text_secondary));
        box.addView(detail);
    }
    public static MaterialButton action(LinearLayout box,String title,View.OnClickListener click) {
        MaterialButton button=new MaterialButton(box.getContext(),null,com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setMinHeight(dp(box.getContext(),52));
        button.setCornerRadius(dp(box.getContext(),14));
        button.setOnClickListener(click);
        box.addView(button,new LinearLayout.LayoutParams(-1,-2));
        return button;
    }
    public static BottomSheetDialog show(Context context,LinearLayout content) {
        BottomSheetDialog dialog=new BottomSheetDialog(context);
        ScrollView scroll=new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }
}
