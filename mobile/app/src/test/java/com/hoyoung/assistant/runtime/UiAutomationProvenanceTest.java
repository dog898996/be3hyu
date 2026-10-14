package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Window;
import android.app.Dialog;
import java.lang.reflect.Proxy;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class UiAutomationProvenanceTest {
    private Context context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("hoyoung_ui_actor_v1",Context.MODE_PRIVATE).edit().clear().commit();}
    @After public void cleanup(){context.getSharedPreferences("hoyoung_ui_actor_v1",Context.MODE_PRIVATE).edit().clear().commit();}

    @Test public void ownAppActionsMarkOriginWithoutPersistingSuppliedInput(){
        UiAutomationProvenance.record(context,2097152,true,"fixture input never persisted");assertEquals(UiAutomationProvenance.AGENT,UiAutomationProvenance.currentActor(context));assertFalse(context.getSharedPreferences("hoyoung_ui_actor_v1",0).getAll().toString().contains("fixture input"));
    }

    @Test public void externalAppOperationDoesNotContaminateOwnerComposer(){
        UiAutomationProvenance.record(context,16,false,null);assertEquals(UiAutomationProvenance.OWNER,UiAutomationProvenance.currentActor(context));
    }

    @Test public void generatedTouchRetainsAgentOriginAndRealTouchRestoresOwnerControl(){
        UiAutomationProvenance.record(context,0,true,null);MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();coordinates.x=10;coordinates.y=10;coordinates.pressure=1;coordinates.size=1;
        MotionEvent injected=MotionEvent.obtain(0,1,MotionEvent.ACTION_DOWN,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coordinates},0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0x800);
        try{UiAutomationProvenance.observeTouch(context,injected);assertEquals(UiAutomationProvenance.AGENT,UiAutomationProvenance.currentActor(context));}finally{injected.recycle();}
        MotionEvent physical=MotionEvent.obtain(0,2,MotionEvent.ACTION_DOWN,10,10,0);try{UiAutomationProvenance.observeTouch(context,physical);assertEquals(UiAutomationProvenance.OWNER,UiAutomationProvenance.currentActor(context));}finally{physical.recycle();}
    }

    @Test public void sendingByAutomationDoesNotConvertEitherAuthorshipToOwner(){
        assertEquals(UiAutomationProvenance.AGENT,UiAutomationProvenance.submittedActor(UiAutomationProvenance.OWNER,UiAutomationProvenance.AGENT));assertEquals(UiAutomationProvenance.MIXED,UiAutomationProvenance.submittedActor(UiAutomationProvenance.AGENT,UiAutomationProvenance.OWNER));assertEquals(UiAutomationProvenance.OWNER,UiAutomationProvenance.submittedActor(UiAutomationProvenance.OWNER,UiAutomationProvenance.OWNER));assertTrue(UiAutomationProvenance.isOwner(UiAutomationProvenance.MIXED));assertFalse(UiAutomationProvenance.isOwner(UiAutomationProvenance.AGENT));
    }

    @Test public void humanHardwareKeyRestoresOwnerOrigin(){
        UiAutomationProvenance.record(context,16,true,null);UiAutomationProvenance.observeKey(context,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A));assertEquals(UiAutomationProvenance.OWNER,UiAutomationProvenance.currentActor(context));
    }

    @Test public void dialogWindowTouchIsObservedAndExistingWindowCallbackIsPreserved(){
        Dialog dialog=new Dialog(context);int[] calls={0};Window.Callback previous=(Window.Callback)Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),new Class<?>[]{Window.Callback.class},(proxy,method,args)->{if("dispatchTouchEvent".equals(method.getName())){calls[0]++;return true;}return null;});dialog.getWindow().setCallback(previous);
        UiAutomationProvenance.observeWindow(context,dialog.getWindow());Window.Callback wrapped=dialog.getWindow().getCallback();UiAutomationProvenance.observeWindow(context,dialog.getWindow());assertSame(wrapped,dialog.getWindow().getCallback());
        UiAutomationProvenance.record(context,16,true,null);MotionEvent physical=MotionEvent.obtain(0,2,MotionEvent.ACTION_DOWN,10,10,0);try{assertTrue(wrapped.dispatchTouchEvent(physical));assertEquals(1,calls[0]);assertEquals(UiAutomationProvenance.OWNER,UiAutomationProvenance.currentActor(context));}finally{physical.recycle();}
    }
}
