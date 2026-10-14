package com.hoyoung.assistant.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** Labels who operates this app; it never grants, denies or asks approval for an action.
 * An agent operating its own composer is not a new utterance by the phone's owner.
 * Only the origin marker is persisted here. Supplied text and credentials are never stored.
 */
public final class UiAutomationProvenance {
    public static final String OWNER = "owner";
    public static final String AGENT = "agent_authored";
    public static final String MIXED = "owner_with_agent_input";
    private static final String PREFERENCES = "hoyoung_ui_actor_v1";
    private static final String ACTOR = "currentActor";
    // Android framework MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT is documented as 0x800
    // in AOSP (a hidden constant). Reading public getFlags() needs no hidden API access.
    private static final int ACCESSIBILITY_EVENT_FLAG = 0x800;

    private UiAutomationProvenance() {}

    /** Must precede the actual own-app accessibility action, including asynchronous gestures.
     * suppliedText is intentionally unused; secret values never enter this provenance record. */
    public static void record(Context context, int action, boolean ownApp, String suppliedText) {
        if (!ownApp) return;
        setActor(context, AGENT);
    }

    /** A real touch starts an owner interaction. Generated accessibility touches retain the
     * agent origin so a synthetic Send tap cannot become a forged owner message. */
    public static void observeTouch(Context context, MotionEvent event) {
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        if ((event.getFlags() & ACCESSIBILITY_EVENT_FLAG) != 0) return;
        setActor(context, OWNER);
    }

    public static void observeKey(Context context, KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || (event.getFlags() & ACCESSIBILITY_EVENT_FLAG) != 0) return;
        setActor(context, OWNER);
    }

    /** Dialogs have separate windows from their Activity. Preserve every existing callback
     * while observing their real touch/key origin, including owner confirmation controls. */
    public static void observeWindow(Context context, Window window) {
        if (window == null || window.getCallback() == null) return;
        Window.Callback previous = window.getCallback();
        if (Proxy.isProxyClass(previous.getClass()) && Proxy.getInvocationHandler(previous) instanceof ActorWindowCallback) return;
        window.setCallback((Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},new ActorWindowCallback(context,previous)));
    }

    private static final class ActorWindowCallback implements java.lang.reflect.InvocationHandler {
        private final Context context;private final Window.Callback previous;
        ActorWindowCallback(Context context,Window.Callback previous){this.context=context;this.previous=previous;}
        @Override public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] arguments)throws Throwable {
            if(arguments!=null&&arguments.length==1){
                if("dispatchTouchEvent".equals(method.getName())&&arguments[0] instanceof MotionEvent)observeTouch(context,(MotionEvent)arguments[0]);
                else if("dispatchKeyEvent".equals(method.getName())&&arguments[0] instanceof KeyEvent)observeKey(context,(KeyEvent)arguments[0]);
            }
            try{return method.invoke(previous,arguments);}catch(InvocationTargetException failed){throw failed.getCause();}
        }
    }

    public static String currentActor(Context context) {
        return normalize(preferences(context).getString(ACTOR, OWNER));
    }

    public static String normalize(String actor) {
        if (OWNER.equals(actor) || AGENT.equals(actor) || MIXED.equals(actor)) return actor;
        throw new IllegalArgumentException("Unknown input actor");
    }

    /** MIXED means a human submitted/adopted agent-assisted text and has owner authority. */
    public static boolean isOwner(String actor) { return !AGENT.equals(normalize(actor)); }

    /** Authorship and submission are separate. A human can adopt an AI-written draft;
     * automation cannot adopt an owner's unsent text on their behalf. */
    public static String submittedActor(String draftActor, String controlActor) {
        normalize(draftActor);normalize(controlActor);
        if (AGENT.equals(controlActor)) return AGENT;
        return OWNER.equals(draftActor)&&OWNER.equals(controlActor)?OWNER:MIXED;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void setActor(Context context,String actor) {
        SharedPreferences values=preferences(context);
        // Ordinary owner taps do not write storage. Only a genuine origin transition does.
        if(!actor.equals(values.getString(ACTOR,OWNER)))values.edit().putString(ACTOR,actor).commit();
    }
}
