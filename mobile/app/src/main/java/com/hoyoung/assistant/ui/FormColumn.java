package com.hoyoung.assistant.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

/** Spacing and input styling for native settings; controls keep their original listeners. */
public final class FormColumn extends LinearLayout {
    public FormColumn(Context context){super(context);setOrientation(VERTICAL);}
    @Override public void addView(View child,int index,ViewGroup.LayoutParams params){
        LinearLayout.LayoutParams spaced=params instanceof LinearLayout.LayoutParams?(LinearLayout.LayoutParams)params:new LinearLayout.LayoutParams(params==null?new ViewGroup.LayoutParams(-1,-2):params);
        spaced.bottomMargin=Math.max(spaced.bottomMargin,ChatPalette.dp(getContext(),10));
        if(child instanceof EditText){
            EditText field=(EditText)child;field.setTextSize(15);field.setTextColor(ChatPalette.INK);field.setHintTextColor(ChatPalette.MUTED);field.setMinHeight(ChatPalette.dp(getContext(),52));
            field.setPadding(ChatPalette.dp(getContext(),14),ChatPalette.dp(getContext(),12),ChatPalette.dp(getContext(),14),ChatPalette.dp(getContext(),12));
            field.setBackground(ChatPalette.outlined(getContext(),Color.WHITE,ChatPalette.BORDER,14));
            if(field.getContentDescription()==null&&field.getHint()!=null)field.setContentDescription(field.getHint());
        }else if(child instanceof CompoundButton){
            CompoundButton toggle=(CompoundButton)child;toggle.setButtonTintList(ColorStateList.valueOf(ChatPalette.ACCENT));toggle.setTextColor(ChatPalette.INK);toggle.setTextSize(14);toggle.setMinHeight(ChatPalette.dp(getContext(),48));
        }else if(child instanceof Spinner){
            child.setMinimumHeight(ChatPalette.dp(getContext(),52));child.setBackground(ChatPalette.outlined(getContext(),Color.WHITE,ChatPalette.BORDER,14));child.setPadding(ChatPalette.dp(getContext(),12),0,ChatPalette.dp(getContext(),12),0);
        }
        super.addView(child,index,spaced);
    }
}
