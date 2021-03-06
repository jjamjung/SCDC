package kr.ac.snu.imlab.scdc.service.probe;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.TrafficStats;
import android.util.Log;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import edu.mit.media.funf.Schedule;
import edu.mit.media.funf.probe.Probe;
import edu.mit.media.funf.probe.builtin.ProbeKeys;
import edu.mit.media.funf.time.DecimalTimeUnit;
import edu.mit.media.funf.time.TimeUtil;
import kr.ac.snu.imlab.scdc.service.core.SCDCKeys;

@Probe.DisplayName("Network Traffic Probe")
@Probe.Description("records network traffic(mobile data network, all network) generated for interval in bytes. also records network traffic with applications.")
@Schedule.DefaultSchedule(interval = 60)
public class NetworkTrafficProbe extends InsensitiveProbe implements Probe.ContinuousProbe {

    private static final String TAG = "debug1013";
    private TrafficStatsDummy trafficStatsCurrent;
    private TrafficStatsDummy trafficStatsPast;
    public BigDecimal currTimestamp = null;
    public BigDecimal lastTimestamp = null;

    private double checkInterval = 15.0;
    private TrafficChecker trafficChecker = new TrafficChecker();


    private class TrafficChecker implements Runnable {

        @Override
        public void run() {
            trafficStatsCurrent = snapTrafficStatsCurrent();
            if (trafficStatsPast != null) {
                sendTraffic(trafficDataList(), false);
            }
            setTrafficStatsPast();
            getHandler().postDelayed(this, TimeUtil.secondsToMillis(checkInterval));
        }

        public void endCurrentTask() {
            reset();
        }

        public void reset() {
            trafficStatsCurrent = null;
            trafficStatsPast = null;
            currTimestamp = null;
            lastTimestamp = null;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        onContinue();
    }

    protected void onContinue() {
        getHandler().post(trafficChecker);
    }

    protected void onPause() {
        getHandler().removeCallbacks(trafficChecker);
        trafficChecker.endCurrentTask();
    }

    private TrafficStatsDummy snapTrafficStatsCurrent() {
        Log.d(SCDCKeys.LogKeys.DEB, "[" + probeName + "] snapTrafficStatsCurrent");
        currTimestamp = TimeUtil.getTimestamp();

        TrafficStatsDummy currentTrafficStats = new TrafficStatsDummy();
        currentTrafficStats.totalRxBytes = TrafficStats.getTotalRxBytes();
        currentTrafficStats.totalTxBytes = TrafficStats.getTotalTxBytes();
        currentTrafficStats.mobileRxBytes = TrafficStats.getMobileRxBytes();
        currentTrafficStats.mobileTxBytes = TrafficStats.getMobileTxBytes();

        for (ApplicationInfo applicationInfo : ApplicationInfoInstalled()) {
            currentTrafficStats.applicationUIdAndPackageName.put(applicationInfo.uid, applicationInfo.packageName);
            currentTrafficStats.applicationRxBytes.put(applicationInfo.uid, TrafficStats.getUidRxBytes(applicationInfo.uid));
            currentTrafficStats.applicationTxBytes.put(applicationInfo.uid, TrafficStats.getUidTxBytes(applicationInfo.uid));
        }

        return currentTrafficStats;
    }

    private List<ApplicationInfo> ApplicationInfoInstalled() {
        PackageManager pm = getContext().getPackageManager();
        return pm.getInstalledApplications(PackageManager.GET_META_DATA);
    }

    private ArrayList<JsonObject> trafficDataList() {
        ArrayList<JsonObject> trafficDataList = new ArrayList<>();

        trafficDataList.add(totalTraffic());
        trafficDataList.add(mobileTraffic());

        for (int uid : trafficStatsCurrent.applicationUIdAndPackageName.keySet()) {
            JsonObject applicationTraffic = getApplicationTrafficWithUId(uid);
            if (applicationTraffic != null) {
                trafficDataList.add(applicationTraffic);
            }
        }

        return trafficDataList;
    }

    private JsonObject totalTraffic() {
        JsonObject traffic = new JsonObject();
        traffic.addProperty(SCDCKeys.NetworkTrafficKeys.WHERE, SCDCKeys.NetworkTrafficKeys.TOTAL_WHERE_VALUE);
        traffic.addProperty(SCDCKeys.NetworkTrafficKeys.RECEIVED, trafficStatsCurrent.totalRxBytes - trafficStatsPast.totalRxBytes);
        traffic.addProperty(SCDCKeys.NetworkTrafficKeys.TRANSMITTED, trafficStatsCurrent.totalTxBytes - trafficStatsPast.totalTxBytes);
        return traffic;
    }

    private JsonObject mobileTraffic() {
        JsonObject traffic = new JsonObject();
        traffic.addProperty(SCDCKeys.NetworkTrafficKeys.WHERE, SCDCKeys.NetworkTrafficKeys.MOBILE_WHERE_VALUE);
        traffic.addProperty(SCDCKeys.NetworkTrafficKeys.RECEIVED, trafficStatsCurrent.mobileRxBytes - trafficStatsPast.mobileRxBytes);
        traffic.addProperty(SCDCKeys.NetworkTrafficKeys.TRANSMITTED, trafficStatsCurrent.mobileTxBytes - trafficStatsPast.mobileTxBytes);
        return traffic;
    }

    private JsonObject getApplicationTrafficWithUId(int uid) {
        if (!trafficStatsPast.applicationUIdAndPackageName.keySet().contains(uid)) {
            return null;
        } else {
            long trafficRx = trafficStatsCurrent.applicationRxBytes.get(uid) - trafficStatsPast.applicationRxBytes.get(uid);
            long trafficTx = trafficStatsCurrent.applicationTxBytes.get(uid) - trafficStatsPast.applicationTxBytes.get(uid);
            if (trafficRx == 0 && trafficTx == 0) {
                return null;
            } else {
                JsonObject applicationTraffic = new JsonObject();
                applicationTraffic.addProperty(SCDCKeys.NetworkTrafficKeys.WHERE, trafficStatsCurrent.applicationUIdAndPackageName.get(uid));
                applicationTraffic.addProperty(SCDCKeys.NetworkTrafficKeys.RECEIVED, trafficRx);
                applicationTraffic.addProperty(SCDCKeys.NetworkTrafficKeys.TRANSMITTED, trafficTx);
                return applicationTraffic;
            }
        }
    }

    private void sendTraffic(ArrayList<JsonObject> trafficDataList, boolean isUrgent) {
        BigDecimal duration = currTimestamp.subtract(lastTimestamp);
        if (isValidTrafficDataList(trafficDataList)) {
            for (JsonObject trafficData : trafficDataList) {
                trafficData.addProperty(SCDCKeys.InsensitiveKeys.DURATION, duration);
                trafficData.addProperty(ProbeKeys.BaseProbeKeys.TIMESTAMP, lastTimestamp);
                if (isUrgent) trafficData.addProperty(SCDCKeys.InsensitiveKeys.IS_URGENT, true);
                sendData(trafficData);
            }
        }
    }

    private boolean isValidTrafficDataList(ArrayList<JsonObject> trafficDataList) {
        if (trafficDataList == null) {
            return false;
        } else {
            for (JsonObject trafficData : trafficDataList) {
                if (trafficData.get(SCDCKeys.NetworkTrafficKeys.RECEIVED).getAsLong() < 0
                        || trafficData.get(SCDCKeys.NetworkTrafficKeys.TRANSMITTED).getAsLong() < 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private void setTrafficStatsPast() {
        trafficStatsPast = trafficStatsCurrent;
        lastTimestamp = currTimestamp;
    }

    @Override
    protected void onStop() {
        onPause();
        super.onStop();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        trafficChecker.reset();
    }

    @Override
    public void sendFinalData() {
        if (trafficStatsPast != null) {
            Long currTime = System.currentTimeMillis();
            if (DecimalTimeUnit.MILLISECONDS.toSeconds(currTime).longValue() > lastTimestamp.longValue() + 5) {
                trafficStatsCurrent = snapTrafficStatsCurrent();
                sendTraffic(trafficDataList(), true);
                Log.d(SCDCKeys.LogKeys.DEB, "[" + probeName + "] sendFinalData!");
                trafficStatsPast = null;
            }
        }
    }

    private class TrafficStatsDummy {

        public long mobileRxBytes;
        public long mobileTxBytes;
        public long totalRxBytes;
        public long totalTxBytes;
        public HashMap<Integer, String> applicationUIdAndPackageName;
        public HashMap<Integer, Long> applicationRxBytes;
        public HashMap<Integer, Long> applicationTxBytes;

        public TrafficStatsDummy() {
            applicationUIdAndPackageName = new HashMap<>();
            applicationRxBytes = new HashMap<>();
            applicationTxBytes = new HashMap<>();
        }
    }

}