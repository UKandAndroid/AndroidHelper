package com.helper.lib;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * Created by Ubaid on 24/06/2016.
 */

// Class to keep CPU awake, or awake at certain time
public class Wake extends BroadcastReceiver {
    private int iCurAction = -1;
    private long iCurActionTime = 0;
    private static Wake instance;
    private AlarmManager alarmMgr;
    private static Context context;
    private PendingIntent alarmIntent;
    private static Flow.Code actionCode;
    private static PowerManager.WakeLock wakeLock;
    private static List<Long> listActionTime = new ArrayList<>();
    private static List<Long> listRepeatTime = new ArrayList<>();
    private static List<Integer> listAction = new ArrayList<>();
    private SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
    private static final String ALARM_INTENT = "com.lib.receiver.Wake";
    private static int API_VERSION = Build.VERSION.SDK_INT;
    private static String LOG_TAG =  "Wake";

    // CONSTRUCTOR to be called by Intent service, Don't use, instead used init() to initialize the class
    public Wake(){}

    // METHOD to get instance of the class, as class is singleton
    public Wake instance(){
        if(instance == null){ throw  new RuntimeException("Class not initialised, use init(Context, Flow.Code )");}
        return instance;
    }

    public int pendingCount(){
        return listActionTime.size();
    }
    // METHOD to initialise the class, as class is singleton
    public static Wake init(Context con, Flow.Code flowCode){
        context = con;
        instance = new Wake();
        actionCode = flowCode;
        listAction.clear();
        listActionTime.clear();
        listRepeatTime.clear();
        instance.iCurAction = -1;                                      // So same event can be loaded again, in-case static variable is still same

        context.registerReceiver(new Wake(), new IntentFilter(ALARM_INTENT));
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK , "Wake");

        loadPendingActions();
        instance.setNextTimer();
        return instance;
    }

    // METHOD to release wake lock, when not set for fixed time
    public void release(){ wakeLock.release(); }
    public void stay(){ wakeLock.acquire(); }
    public static void stay(long iDuration){ wakeLock.acquire(iDuration); }

    // METHOD Wake CPU up, and sends action to be execute, Note, this only waits for one Action, new call will override last Action
    // NOTE: the resolution for delayed call is 5 second, anything smaller then that will be called after 5 seconds
    public void runRepeat(int iAction, long timeMillis) {runDelayed(iAction, timeMillis, true); }
    public void runDelayed(int iAction, long timeMillis) { runDelayed(iAction, timeMillis, false);}
    private void runDelayed(int iAction,  long timeMillis, boolean bRepeat) {
  //      Log.w(LOG_TAG, "runDelayed Action: " + iAction + (bRepeat? " Repeat " :""));
        boolean bAdd = true;
        long iTime = System.currentTimeMillis() + timeMillis;
        int iSize = listAction.size();
        if(bRepeat){                                                // If its a repeating alarm, check it already does not exist
            for(int i=0; i< iSize; i++){                            // If it does, remove it, so there is always one repeating alarm
                if(listAction.get(i) == iAction) {                   // for a action
                    removeAction(i);
                    iSize = listActionTime.size();
                }}}

        for(int i = 0; i < iSize; i++ ){
            if(iTime < listActionTime.get(i)){
                bAdd = false;
                listActionTime.add(i, iTime);
                listAction.add(i, iAction);
                listRepeatTime.add(i, bRepeat ? timeMillis : 0L);
                break;}}

        if(bAdd){
            listActionTime.add(iTime);
            listAction.add(iAction);
            listRepeatTime.add(bRepeat ? timeMillis : 0L);
        }

        saveActions();
        setNextTimer();
    }

    @TargetApi(23)
    // METHOD is called, when a timer goes off, to set next timer
    private synchronized void setNextTimer(){
        boolean bNewAction = false;
        int iSize = listAction.size();
        // If we have a action in list, and its not same as old one
        if(iSize > 0 && (iCurAction != listAction.get(0) || iCurActionTime != listActionTime.get(0)))
            bNewAction = true;

        if(bNewAction) {
            iCurAction = listAction.get(0);
            iCurActionTime = listActionTime.get(0);
            Intent intent = new Intent(ALARM_INTENT);
            intent.putExtra("action", iCurAction);
            alarmIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, iCurActionTime, alarmIntent);

            if (API_VERSION >= Build.VERSION_CODES.M){       // In marshmallow, use this as setExact will be blocked
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, iCurActionTime, alarmIntent);
            } else{
                alarmMgr.setExact(AlarmManager.RTC_WAKEUP, iCurActionTime, alarmIntent);
            }
            Log.d(LOG_TAG, "Run Action: " +iCurAction + " > "+ sdf.format(new Date(iCurActionTime)) + (listRepeatTime.get(0)> 0 ? " Repeat" : "") );
        }
    }

    // METHOD cancels an action Run
    public void cancelRun(int iAction){
        if(iAction == iCurAction){   // its the current pending action, cancel it and set the next one
            Log.w(LOG_TAG, "Cancelled Action: "+ iAction );
            if(alarmIntent != null) {
                AlarmManager mgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                mgr.cancel(alarmIntent);
                if(listActionTime.size() > 0){
                    removeAction(0);
                    setNextTimer();}}
        } else {                   // Remove action from the list
            for(int i=0; i< listAction.size(); i++){
                if(iAction == listAction.get(i)){
                    removeAction(i);
                    Log.w(LOG_TAG, "Cancelled Action: " + iAction);
                }}
        }
    }

    // RECEIVER called when wake up timer is fired
    @Override public void onReceive(Context con, Intent intent) {
        boolean bNextNow = false;
        Log.w(LOG_TAG, "Exe Action: " + intent.getIntExtra("action", 0) + " > " + sdf.format(new Date()));

        if(actionCode != null)
            actionCode.onAction(intent.getIntExtra("action", 0), true, 0, null);

        if(listAction.size() > 0){
            if(listRepeatTime.get(0)> 0){       // its a repeat message, add it again
                runDelayed(listAction.get(0), listRepeatTime.get(0), true);
            }  else {
                removeAction(0);
                saveActions();
            }

            long iNextActionTime = listActionTime.get(0);
            if(iNextActionTime < System.currentTimeMillis()+1000L){                                // If next action is already late or time is less then 1 second, run it now
                intent = new Intent(ALARM_INTENT);
                iCurAction = listAction.get(0);
                intent.putExtra("action", iCurAction);
                bNextNow = true;
            }

            if(bNextNow)
                onReceive(con, intent);
            else
                setNextTimer();
        }
    }

    // METHOD removes an Action from lists
    private static void removeAction(int iIndex){
        listActionTime.remove(iIndex);
        listAction.remove(iIndex);
        listRepeatTime.remove(iIndex);
    }


    // METHOD cancels any pending alarms
    public void cancelPending(){
        Log.d(LOG_TAG, "Cancel Pending");
        if(alarmIntent == null) {
            Intent intent = new Intent(context, Wake.class);
            alarmIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
            alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        }
        alarmMgr.cancel(alarmIntent);
    }

    // Stop the Wake class completely
    public void stop(){
        listAction.clear();
        listActionTime.clear();
        listRepeatTime.clear();
        cancelPending();
        instance = null;
    }

    // METHOD saves list to prefs, in case the app restarts
    private static void saveActions(){
        String sTime = "", sRepeat = "", sAction = "";
        SharedPreferences sharedPref = context.getSharedPreferences("WakeListener", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        int iSize = listActionTime.size();
        for(int i=0; i < iSize; i++){
            sTime += listActionTime.get(i).toString();
            sRepeat += listRepeatTime.get(i).toString();
            sAction += listAction.get(i).toString();
            if(i < iSize-1){
                sTime += ",";
                sRepeat += ",";
                sAction += ",";
            }
        }

        editor.putString("time", sTime);
        editor.putString("repeat", sRepeat);
        editor.putString("action", sAction);
        editor.apply();
    }

    // METHOD loads list from prefs, when starts for first time
    private static void loadPendingActions(){
        SharedPreferences sharedPref = context.getSharedPreferences("WakeListener", Context.MODE_PRIVATE);
        String sTime = sharedPref.getString("time", "");
        String sRepeat = sharedPref.getString("repeat", "");
        String sAction = sharedPref.getString("action", "");

        if(!sTime.equals("")){
            String arrTime[] = sTime.split(",");
            String arrRepeat[] = sRepeat.split(",");
            String arrAction[] = sAction.split(",");
            Log.e(LOG_TAG, arrTime.length + " Pending actions loaded.");

            for(int i=0; i < arrTime.length; i++){
                listActionTime.add(Long.parseLong(arrTime[i]));
                listRepeatTime.add(Long.parseLong(arrRepeat[i]));
                listAction.add(Integer.parseInt(arrAction[i]));
            }}
    }

}