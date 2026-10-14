package com.hoyoung.assistant.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import com.hoyoung.assistant.R;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** Owner-selected bundled photo, shared at bounded decode size and circle-cropped only at draw time. */
public final class HoyoungAvatarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader gradient;
    private static Bitmap profile;
    private BitmapShader profileShader;
    private final Matrix crop=new Matrix();

    public HoyoungAvatarView(Context context) {
        super(context); setContentDescription("호영 프로필 사진");
        Bitmap photo=profilePhoto(context);
        if(photo!=null)profileShader=new BitmapShader(photo,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMinimumWidth(ChatPalette.dp(context, 42)); setMinimumHeight(ChatPalette.dp(context, 42));
    }
    private static synchronized Bitmap profilePhoto(Context context){
        if(profile!=null&&!profile.isRecycled())return profile;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;bounds.inScaled=false;
        BitmapFactory.decodeResource(context.getResources(),R.drawable.hoyoung_profile,bounds);
        BitmapFactory.Options decode=new BitmapFactory.Options();decode.inScaled=false;
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2;decode.inSampleSize=sample;
        try{profile=BitmapFactory.decodeResource(context.getResources(),R.drawable.hoyoung_profile,decode);}catch(RuntimeException unavailable){profile=null;}
        return profile;
    }
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec), resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        gradient = new LinearGradient(0, 0, w, h, ChatPalette.ACCENT, ChatPalette.ACCENT_END, Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
        if(profileShader!=null&&profile!=null){
            float scale=Math.max(size/profile.getWidth(),size/profile.getHeight());crop.reset();crop.setScale(scale,scale);crop.postTranslate(cx-profile.getWidth()*scale/2f,cy-profile.getHeight()*scale/2f);profileShader.setLocalMatrix(crop);
            paint.setShader(profileShader);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(cx,cy,size/2f,paint);paint.setShader(null);return;
        }
        paint.setShader(gradient); paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, size / 2f, paint); paint.setShader(null);
        paint.setColor(Color.WHITE); paint.setAlpha(18);
        canvas.drawCircle(cx - size * .24f, cy - size * .23f, size * .39f, paint);
        paint.setAlpha(255); paint.setTextSize(size * .43f);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("호", cx, cy - (paint.ascent() + paint.descent()) / 2f, paint);
    }
}
