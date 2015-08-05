package kr.ac.snu.imlab.scdc.activity;

import android.content.Context;
 import android.content.SharedPreferences;
 import android.support.v7.app.ActionBarActivity;
 import android.content.ComponentName;
 import android.content.Intent;
 import android.content.ServiceConnection;
 import android.database.sqlite.SQLiteDatabase;
 import android.os.Bundle;
 import android.os.Handler;
 import android.util.Log;
 import android.view.Menu;
 import android.view.MenuItem;
 import android.view.View;
 import android.view.WindowManager;
 import android.widget.Button;
 import android.widget.CompoundButton;
 import android.widget.CompoundButton.OnCheckedChangeListener;

 import com.google.gson.JsonElement;
 import com.google.gson.JsonObject;

 import edu.mit.media.funf.FunfManager;
 import edu.mit.media.funf.Schedule.BasicSchedule;
 import edu.mit.media.funf.config.Configurable;
 import edu.mit.media.funf.config.HttpConfigUpdater;
 import edu.mit.media.funf.probe.builtin.*;
import kr.ac.snu.imlab.scdc.service.alarm.LabelAlarm;
import kr.ac.snu.imlab.scdc.service.alarm.TaskButlerService;
import kr.ac.snu.imlab.scdc.service.alarm.WakefulIntentService;
import kr.ac.snu.imlab.scdc.service.SCDCPipeline;
 import kr.ac.snu.imlab.scdc.service.probe.LabelProbe;
 import edu.mit.media.funf.storage.FileArchive;

 import android.os.IBinder;
 import android.widget.EditText;
 import android.widget.ListView;
 import android.widget.RadioButton;
 import android.widget.TextView;
 import android.widget.Toast;
 import android.widget.ToggleButton;

 import java.io.File;
 import java.math.BigDecimal;
 import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
 import java.util.List;
 import java.util.Map;

 import kr.ac.snu.imlab.scdc.service.SCDCKeys.Config;
 import kr.ac.snu.imlab.scdc.service.SCDCKeys.SharedPrefs;
 import kr.ac.snu.imlab.scdc.service.SCDCKeys.LabelKeys;
 import kr.ac.snu.imlab.scdc.service.SCDCKeys.LogKeys;
import kr.ac.snu.imlab.scdc.service.SCDCKeys.AlarmKeys;
 import kr.ac.snu.imlab.scdc.service.storage.MultipartEntityArchive;
 import kr.ac.snu.imlab.scdc.service.storage.SCDCDatabaseHelper;
 import kr.ac.snu.imlab.scdc.service.storage.SCDCUploadService;
 import kr.ac.snu.imlab.scdc.service.storage.ZipArchive;
 import kr.ac.snu.imlab.scdc.adapter.BaseAdapterExLabel;
 import kr.ac.snu.imlab.scdc.entry.LabelEntry;
 import kr.ac.snu.imlab.scdc.entry.ProbeEntry;
 import kr.ac.snu.imlab.scdc.R;
import kr.ac.snu.imlab.scdc.util.SharedPrefsHandler;


public class LaunchActivity extends ActionBarActivity {

     @Configurable
     protected int version = 1;
     @Configurable
     protected FileArchive archive = null;
     @Configurable
     protected MultipartEntityArchive upload = null;

     // The list of labels available
     @Configurable
     public static final String[] labelNames = {
             LabelKeys.SLEEP_LABEL,
             LabelKeys.IN_CLASS_LABEL,
             LabelKeys.EATING_LABEL,
             LabelKeys.STUDYING_LABEL,
             LabelKeys.DRINKING_LABEL
     };

     // The list of probes available
     @Configurable
     public static final Class[] probeClasses = {
             // Device Probes
             BatteryProbe.class,
             // Enironment Probes
             LightSensorProbe.class,
             MagneticFieldSensorProbe.class,
             AudioFeaturesProbe.class,
             // Motion Probes
             AccelerometerSensorProbe.class,
             GyroscopeSensorProbe.class,
             OrientationSensorProbe.class,
             // Positioning Probes
             SimpleLocationProbe.class,
             BluetoothProbe.class,
             // Device Interaction
             RunningApplicationsProbe.class,
             ScreenProbe.class
     };

     private SCDCUploadService uploader;

     private Handler handler;
     private FunfManager funfManager = null;
     private SCDCPipeline pipeline = null;
     private SharedPrefsHandler spHandler = null;

     // Username EditText and Button
     private EditText userName = null;
     private Button userNameButton = null;
     private RadioButton isMaleRadioButton = null;
     private RadioButton isFemaleRadioButton = null;
     boolean isEdited = false;

     // Probe list View
     private ListView mListView = null;
     private BaseAdapterExLabel mAdapter = null;
     // Probes list
     private ArrayList<ProbeEntry> probeEntries;
     private ArrayList<LabelEntry> labelEntries;

     // Run Data Collection button
     private ToggleButton enabledToggleButton;

     // Run Push notification button
     private ToggleButton reminderToggleButton;

     private Button archiveButton, truncateDataButton;
     private TextView dataCountView;

     private ServiceConnection funfManagerConn = new ServiceConnection() {
       @Override
       public void onServiceConnected(ComponentName name, IBinder service) {
         funfManager = ((FunfManager.LocalBinder)service).getManager();
         // funfManager.setCallingActivity(LaunchActivity.this);
         pipeline = (SCDCPipeline)funfManager.getRegisteredPipeline
                 (Config.PIPELINE_NAME);
         pipeline.setActivity(LaunchActivity.this);

         // Update probe schedules of pipeline
         HttpConfigUpdater hcu = new HttpConfigUpdater();
         hcu.setUrl("http://imlab-ws2.snu.ac.kr:7000/config_orig");
         pipeline.setUpdate(hcu);
         handler.post(new Runnable() {
           @Override
           public void run() {
             if (pipeline.getHandler() != null) {
               pipeline.onRun(SCDCPipeline.ACTION_UPDATE, null);
             }
           }
         });


         // This checkbox enables or disables the pipeline
         enabledToggleButton.setChecked(pipeline.isEnabled());
         enabledToggleButton.setOnCheckedChangeListener(new OnCheckedChangeListener() {
           @Override
           public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
             if (funfManager != null) {
               if (isChecked) {
                 funfManager.enablePipeline(pipeline.getName());

                 // Assigning new schedules to existing probes in each probeEntries
                 Map<JsonElement, BasicSchedule> newSchedules =
                   buildScheduleMap(pipeline);
                 for (int i = 0; i < probeEntries.size(); i++) {
                   ProbeEntry probeEntry = probeEntries.get(i);
                   probeEntry.setSchedule(
                     newSchedules.get(probeEntry.getProbeConfig())
                   );
                 }

                 // Request data from pipeline(data listener) to normal probes
                 for (int i = 0; i < probeEntries.size(); i++) {
                   ProbeEntry probeEntry = probeEntries.get(i);
                   if (probeEntry.isEnabled()) {
                     funfManager.requestData(pipeline,
                       probeEntry.getProbeConfig(), probeEntry.getSchedule());
                   } else {
                     funfManager.unrequestData(pipeline,
                       probeEntry.getProbeConfig());
                   }
                 }

                 // Request data from pipeline (data listener) to label probe
                 for (int i = 0; i < labelEntries.size(); i++) {
                   LabelEntry labelEntry = labelEntries.get(i);
                   if (labelEntry.isEnabled()) {
                     funfManager.requestData(pipeline,
                       labelEntry.getProbeConfig(), labelEntry.getSchedule());
                   } else {
                     funfManager.unrequestData(pipeline,
                       labelEntry.getProbeConfig());
                   }
                 }

                 archiveButton.setEnabled(false);
                 truncateDataButton.setEnabled(false);

                 // Intentionally wait 1 second for label probes to be loaded
                 // then send broadcast
                 handler.postDelayed(new Runnable() {
                   @Override
                   public void run() {
                     Log.w(LogKeys.DEBUG, "LaunchActivity/ Entering " +
                             "sendBroadcast(intent)");
                     Intent intent = new Intent();
                     intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                     intent.setAction(LabelKeys.ACTION_LABEL_LOG);
                     for (int i = 0; i < labelEntries.size(); i++) {
                       intent.putExtra(labelEntries.get(i).getName(),
                               labelEntries.get(i).isLogged());
                     }
                     sendBroadcast(intent);
                   }
                 }, 1000L);

                 // Dynamically refresh the ListView items
                 handler.postDelayed(new Runnable() {
                   @Override
                   public void run() {
                     mAdapter.notifyDataSetChanged();
                     handler.postDelayed(this, 1000L);
                   }
                 }, 1000L);

               } else {
                 // Dynamically refresh the ListView items
                 // by calling mAdapter.getView() again.
                 mAdapter.notifyDataSetChanged();

                 Log.w(LogKeys.DEBUG, "LaunchActivity/ Entering sendBroadcast" +
                         "(intent)");
                 Intent intent = new Intent();
                 intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                 intent.setAction(LabelKeys.ACTION_LABEL_LOG);
                 for (int i = 0; i < labelEntries.size(); i++) {
                   intent.putExtra(labelEntries.get(i).getName(),
                           labelEntries.get(i).isLogged());
                 }
                 sendBroadcast(intent);

                 // Intentionally wait 2 seconds to send broadcast
                 // then terminate
                 handler.postDelayed(new Runnable() {
                   @Override
                   public void run() {
                     archiveButton.setEnabled(true);
                     truncateDataButton.setEnabled(true);
                     funfManager.disablePipeline(Config.PIPELINE_NAME);
                   }
                 }, 2000L);
               }

             reminderToggleButton.setEnabled(isChecked);
             }


           }
         });

         // Set UI ready to use, by enabling buttons
         enabledToggleButton.setEnabled(true);

         if (enabledToggleButton.isChecked()) {
             archiveButton.setEnabled(false);
             truncateDataButton.setEnabled(false);
         } else {
             archiveButton.setEnabled(true);
             truncateDataButton.setEnabled(true);
         }

         mAdapter.notifyDataSetChanged();
         updateScanCount();
       }

       @Override
       public void onServiceDisconnected(ComponentName name) {
         funfManager = null;
       }
     };

     @Override
     protected void onCreate(Bundle savedInstanceState) {
       spHandler = SharedPrefsHandler.getInstance(this,
               Config.SCDC_PREFS, Context.MODE_PRIVATE);

       super.onCreate(savedInstanceState);
       setContentView(R.layout.activity_launch);

       // Make sure the keyboard only pops up
       // when a user clicks into an EditText
       this.getWindow().setSoftInputMode(
               WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

       // Set current username
       userName = (EditText)findViewById(R.id.user_name);
       userName.setText(spHandler.getUsername());
       isMaleRadioButton = (RadioButton)findViewById(R.id.radio_male);
       isFemaleRadioButton = (RadioButton)findViewById(R.id.radio_female);
       isMaleRadioButton.setChecked(!spHandler.getIsFemale());
       isFemaleRadioButton.setChecked(spHandler.getIsFemale());
       userName.setEnabled(false);
       isMaleRadioButton.setEnabled(false);
       isFemaleRadioButton.setEnabled(false);
       isEdited = false;

       userNameButton = (Button)findViewById(R.id.user_name_btn);
       userNameButton.setOnClickListener(new View.OnClickListener() {
           @Override
           public void onClick(View v) {
               // If it's currently not being edited now:
               if (!isEdited) {
                 userName.setEnabled(true);
                 isMaleRadioButton.setEnabled(true);
                 isFemaleRadioButton.setEnabled(true);
                 isEdited = true;
                 userNameButton.setText("Save");
               // If it has just finished being edited:
               } else {
                 spHandler.setUsername(userName.getText().toString());
                 spHandler.setIsFemale(isFemaleRadioButton
                         .isChecked());
                 userName.setEnabled(false);
                 isMaleRadioButton.setEnabled(false);
                 isFemaleRadioButton.setEnabled(false);
                 isEdited = false;
                 userNameButton.setText("Modify");
               }
           }
       });


       probeEntries = new ArrayList<ProbeEntry>(probeClasses.length);
       for (int i = 0; i < probeClasses.length; i++) {
         probeEntries.add(new ProbeEntry(probeClasses[i], null, true));
       }

       labelEntries = new ArrayList<LabelEntry>(labelNames.length);
       for (int i = 0; i < labelNames.length; i++) {
         labelEntries.add(new LabelEntry(i, labelNames[i],
                                 LabelProbe.class, null, true,
                                 LaunchActivity.this, Config.SCDC_PREFS));
       }

       // Put the total number of labels into SharedPreferences
       spHandler.setNumLabels(labelEntries.size());

       mAdapter = new BaseAdapterExLabel(this, labelEntries);

       mListView = (ListView)findViewById(R.id.label_list_view);
       mListView.setAdapter(mAdapter);

       // Displays the count of rows in the data
       dataCountView = (TextView)findViewById(R.id.dataCountText);

       // Used to make interface changes on main thread
       handler = new Handler();

       enabledToggleButton =
         (ToggleButton)findViewById(R.id.enabledToggleButton);
       enabledToggleButton.setChecked(false);

       reminderToggleButton =
         (ToggleButton)findViewById(R.id.reminderToggleButton);
       reminderToggleButton.setChecked(false);
       reminderToggleButton.setEnabled(false);
       reminderToggleButton.setOnCheckedChangeListener(
         new OnCheckedChangeListener() {
         @Override
         public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
           if (isChecked) {
             for (LabelEntry labelEntry : labelEntries) {
               if (!labelEntry.isLogged()) {
                 LabelAlarm alarm = new LabelAlarm();
                 if (labelEntry.isRepeating()) {
                   int labelId = alarm.setRepeatingAlarm(LaunchActivity.this,
                                                         labelEntry.getId());
                 } else {
                   if (labelEntry.hasDateDue() && labelEntry.isPastDue())
                     alarm.setAlarm(LaunchActivity.this, labelEntry.getId());
                 }
               }
             }
           } else {
             for (LabelEntry labelEntry : labelEntries) {
               LabelAlarm alarm = new LabelAlarm();
               alarm.cancelAlarm(LaunchActivity.this, labelEntry.getId());
               alarm.cancelNotification(LaunchActivity.this,
                                        labelEntry.getId());
             }
           }
         }
       });


       // Runs an archive if pipeline is enabled
       archiveButton = (Button)findViewById(R.id.archiveButton);
       archiveButton.setEnabled(true);
       archiveButton.setOnClickListener(new View.OnClickListener() {
         @Override
         public void onClick(final View v) {
           v.setEnabled(false);

           Toast.makeText(getBaseContext(), "Compressing DB file. Please wait...",
                   Toast.LENGTH_LONG).show();
           archive = new ZipArchive(funfManager, Config.PIPELINE_NAME);
           uploader = new SCDCUploadService(funfManager);
           uploader.setContext(LaunchActivity.this);
           uploader.start();

           SQLiteDatabase db = pipeline.getWritableDb();
           Log.w(LogKeys.DEBUG, "LaunchActivity/ db.getPath()=" + db.getPath());
           File dbFile = new File(db.getPath());
           db.close();
           archive.add(dbFile);
           upload = new MultipartEntityArchive(funfManager,
                   "http://imlab-ws2.snu.ac.kr:7000/data",
                   LaunchActivity.this);
           uploader.run(archive, upload);
           if (dbFile.exists()) {
             archive.remove(dbFile);
           }

           // uploader.stop();

           // Wait 1 second for archive to finish, then refresh the UI
           // (Note: this is kind of a hack since archiving is seamless
           //         and there are no messages when it occurs)
           handler.postDelayed(new Runnable() {
             @Override
             public void run() {
   //              Toast.makeText(getBaseContext(), "Archived! Will be uploaded " +
   //                              "in a few seconds...",
   //                              Toast.LENGTH_LONG).show();
   //              pipeline.onRun(BasicPipeline.ACTION_ARCHIVE, null);
   //              pipeline.onRun(BasicPipeline.ACTION_UPLOAD, null);
               updateScanCount();
               if (!enabledToggleButton.isEnabled()) {
                 v.setEnabled(true);
               }
             }
           }, 5000L);
         }
       });

       // Truncate the data
       truncateDataButton = (Button)findViewById(R.id.truncateDataButton);
       truncateDataButton.setEnabled(false);
       truncateDataButton.setOnClickListener(new View.OnClickListener() {
         @Override
         public void onClick(View v) {
           dropAndCreateTable();
           dataCountView.setText("Data size: 0.0 MB");
           // updateScanCount();

         }
       });


       // Start service to check for alarms
       WakefulIntentService.acquireStaticLock(this);
       startService(new Intent(this, TaskButlerService.class));

       // Bind to the service, to create the connection with FunfManager
       bindService(new Intent(this, FunfManager.class),
               funfManagerConn, BIND_AUTO_CREATE);
     }

     @Override
      public void onResume() {
        super.onResume();

        // Dynamically refresh the ListView items
        handler.postDelayed(new Runnable() {
          @Override
          public void run() {
            mAdapter.notifyDataSetChanged();
            handler.postDelayed(this, 1000L);
          }
        }, 1000L);

        // stopService(new Intent(this, TaskButlerService.class));
      }

     @Override
     public void onPause() {
       super.onPause();

//       String hour = spHandler.getDefaultHour();
//
//       Calendar dueDateCal = GregorianCalendar.getInstance();
//       dueDateCal.set(Calendar.HOUR_OF_DAY, Integer.valueOf(hour));
//       dueDateCal.set(Calendar.MINUTE, 0);
//       dueDateCal.set(Calendar.SECOND, 0);
//       dueDateCal.set(Calendar.MILLISECOND, 0);
//       if (dueDateCal.getTimeInMillis() < System.currentTimeMillis())
//         dueDateCal.add(Calendar.DAY_OF_YEAR, 1);
//
//       // set repeat interval
//       int repeatInterval = 1;
//
//       // set task due date
//       long dueDateMillis = dueDateCal.getTimeInMillis();
//       boolean isCompleted = false;
//       boolean hasDueDate = true;
//       boolean hasFinalDueDate = true;
//       boolean isRepeating = true;
//       int repeatType = AlarmKeys.MINUTES;
//
//       // Save current isLogged value of labelEntries from SharedPreferences
//       for (LabelEntry labelEntry : labelEntries) {
//         int labelId = labelEntry.getId();
//
//
//         // Set alarms only for the labels not being logged
//         if (!labelEntry.isLogged()) {
//           LabelAlarm alarm = new LabelAlarm();
//           // FIXME: DEBUG:
//           if (spHandler.getIsRepeating(labelId)) {
//             labelId =
//               alarm.setRepeatingAlarm(this, labelId);
//           } else {
//             if (spHandler.getHasDateDue(labelId) &&
//                 spHandler.getIsPastDue(labelId))
//               alarm.setAlarm(this, labelId);
//           }
//         }
//       }
     }


     @Override
     protected void onDestroy() {
       unbindService(funfManagerConn);
       super.onDestroy();
     }

   //  private static final String TOTAL_COUNT_SQL = "SELECT COUNT(*) FROM " +
   //          NameValueDatabaseHelper.DATA_TABLE.name;
     /**
      * Queries the database of the pipeline to determine how many rows of data we have recorded so far.
      */
     public void updateScanCount() {
       if (pipeline.getDatabaseHelper() != null) {
         // Query the pipeline db for the count of rows in the data table
         SQLiteDatabase db = pipeline.getDb();
         final long dbSize = new File(db.getPath()).length();  // in bytes
         //      Cursor mcursor = db.rawQuery(TOTAL_COUNT_SQL, null);
         //      mcursor.moveToFirst();
         // final int count = mcursor.getInt(0);
         // Update interface on main thread
         runOnUiThread(new Runnable() {
           @Override
           public void run() {
             //          dataCountView.setText("Data Count: " + count + "\n Size: "
             //                      + Math.round((dbSize/(1048576.0))*100.0)/100.0 + " MB");
             dataCountView.setText("Data size: " +
                     Math.round((dbSize / (1048576.0)) * 10.0) / 10.0 + " MB");
           }
         });
       }
     }

     /**
      * @author Kilho Kim
      * Truncate table of the database of the pipeline.
      */
     private void dropAndCreateTable() {
       if (pipeline.getDatabaseHelper() != null) {
         SQLiteDatabase db = pipeline.getWritableDb();
         SCDCDatabaseHelper databaseHelper =
                 (SCDCDatabaseHelper)pipeline.getDatabaseHelper();
         databaseHelper.dropAndCreateDataTable(db);
         updateScanCount();
         Toast.makeText(getBaseContext(), "Dropped and re-created data table.",
                 Toast.LENGTH_LONG).show();
       }
     }

     public FunfManager getActivityFunfManager() {
       return funfManager;
     }

     private Map<JsonElement, BasicSchedule>
       buildScheduleMap(SCDCPipeline pipeline) {
       Map<JsonElement, BasicSchedule> newSchedules = new HashMap<>();
       List<JsonElement> newDataRequests = pipeline.getDataRequests();
       for (int i = 0; i < newDataRequests.size(); i++) {
           JsonObject currDataRequests = newDataRequests.get(i).getAsJsonObject();
           JsonElement currType = currDataRequests.get("@type");
           try {
               JsonObject currSchedule = currDataRequests.get("@schedule").getAsJsonObject();
               BasicSchedule newSchedule = new BasicSchedule();
               if (currSchedule.get("interval") != null) {
                   newSchedule.setInterval(new BigDecimal
                           (currSchedule.get("interval").getAsString()));
               }
               if (currSchedule.get("duration") != null) {
                   newSchedule.setDuration(new BigDecimal
                           (currSchedule.get("duration").getAsString()));
               }
               if (currSchedule.get("opportunistic") != null) {
                   newSchedule.setOpportunistic(
                           currSchedule.get("opportunistic").getAsBoolean());
               }
               if (currSchedule.get("strict") != null) {
                   newSchedule.setStrict(
                           currSchedule.get("strict").getAsBoolean());
               }
               newSchedules.put(currType, newSchedule);
           } catch (NullPointerException e) {
               newSchedules.put(currType, null);
           }
       }

       return newSchedules;
     }


     @Override
     public boolean onCreateOptionsMenu(Menu menu) {
         // Inflate the menu; this adds items to the action bar if it is present.
         getMenuInflater().inflate(R.menu.menu_main, menu);
         return true;
     }

     @Override
     public boolean onOptionsItemSelected(MenuItem item) {
         // Handle action bar item clicks here. The action bar will
         // automatically handle clicks on the Home/Up button, so long
         // as you specify a parent activity in AndroidManifest.xml.
         int id = item.getItemId();

         //noinspection SimplifiableIfStatement
         if (id == R.id.action_settings) {
             return true;
         }

         return super.onOptionsItemSelected(item);
     }
   }
