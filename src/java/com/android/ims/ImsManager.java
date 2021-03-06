/*
 * Copyright (c) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ims;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsServiceProxy;
import android.telephony.ims.ImsServiceProxyCompat;
import android.telephony.ims.feature.ImsFeature;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsServiceController;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.ImsCallSession;
import com.android.ims.internal.IImsConfig;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TelephonyProperties;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Provides APIs for IMS services, such as initiating IMS calls, and provides access to
 * the operator's IMS network. This class is the starting point for any IMS actions.
 * You can acquire an instance of it with {@link #getInstance getInstance()}.</p>
 * <p>The APIs in this class allows you to:</p>
 *
 * @hide
 */
public class ImsManager {

    /*
     * Debug flag to override configuration flag
     */
    public static final String PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE = "persist.dbg.volte_avail_ovr";
    public static final int PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE_DEFAULT = 0;
    public static final String PROPERTY_DBG_VT_AVAIL_OVERRIDE = "persist.dbg.vt_avail_ovr";
    public static final int PROPERTY_DBG_VT_AVAIL_OVERRIDE_DEFAULT = 0;
    public static final String PROPERTY_DBG_WFC_AVAIL_OVERRIDE = "persist.dbg.wfc_avail_ovr";
    public static final int PROPERTY_DBG_WFC_AVAIL_OVERRIDE_DEFAULT = 0;
    public static final String PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE = "persist.dbg.allow_ims_off";
    public static final int PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE_DEFAULT = 0;

    /**
     * For accessing the IMS related service.
     * Internal use only.
     * @hide
     */
    private static final String IMS_SERVICE = "ims";

    /**
     * The result code to be sent back with the incoming call {@link PendingIntent}.
     * @see #open(PendingIntent, ImsConnectionStateListener)
     */
    public static final int INCOMING_CALL_RESULT_CODE = 101;

    /**
     * Key to retrieve the call ID from an incoming call intent.
     * @see #open(PendingIntent, ImsConnectionStateListener)
     */
    public static final String EXTRA_CALL_ID = "android:imsCallID";

    /**
     * Action to broadcast when ImsService is up.
     * Internal use only.
     * @deprecated
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_UP =
            "com.android.ims.IMS_SERVICE_UP";

    /**
     * Action to broadcast when ImsService is down.
     * Internal use only.
     * @deprecated
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_DOWN =
            "com.android.ims.IMS_SERVICE_DOWN";

    /**
     * Action to broadcast when ImsService registration fails.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_IMS_REGISTRATION_ERROR =
            "com.android.ims.REGISTRATION_ERROR";

    /**
     * Part of the ACTION_IMS_SERVICE_UP or _DOWN intents.
     * A long value; the phone ID corresponding to the IMS service coming up or down.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_PHONE_ID = "android:phone_id";

    /**
     * Action for the incoming call intent for the Phone app.
     * Internal use only.
     * @hide
     */
    public static final String ACTION_IMS_INCOMING_CALL =
            "com.android.ims.IMS_INCOMING_CALL";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * An integer value; service identifier obtained from {@link ImsManager#open}.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_SERVICE_ID = "android:imsServiceId";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * An boolean value; Flag to indicate that the incoming call is a normal call or call for USSD.
     * The value "true" indicates that the incoming call is for USSD.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_USSD = "android:ussd";

    /**
     * Part of the ACTION_IMS_INCOMING_CALL intents.
     * A boolean value; Flag to indicate whether the call is an unknown
     * dialing call. Such calls are originated by sending commands (like
     * AT commands) directly to modem without Android involvement.
     * Even though they are not incoming calls, they are propagated
     * to Phone app using same ACTION_IMS_INCOMING_CALL intent.
     * Internal use only.
     * @hide
     */
    public static final String EXTRA_IS_UNKNOWN_CALL = "android:isUnknown";

    private static final String TAG = "ImsManager";
    private static final boolean DBG = true;

    private static HashMap<Integer, ImsManager> sImsManagerInstances =
            new HashMap<Integer, ImsManager>();

    private Context mContext;
    private CarrierConfigManager mConfigManager;
    private int mPhoneId;
    private final boolean mConfigDynamicBind;
    private ImsServiceProxyCompat mImsServiceProxy = null;
    private ImsServiceDeathRecipient mDeathRecipient = new ImsServiceDeathRecipient();
    // Ut interface for the supplementary service configuration
    private ImsUt mUt = null;
    // Interface to get/set ims config items
    private ImsConfig mConfig = null;
    private boolean mConfigUpdated = false;

    private ImsConfigListener mImsConfigListener;

    // ECBM interface
    private ImsEcbm mEcbm = null;

    private ImsMultiEndpoint mMultiEndpoint = null;

    private Set<ImsServiceProxy.INotifyStatusChanged> mStatusCallbacks = new HashSet<>();

    // Keep track of the ImsRegistrationListenerProxys that have been created so that we can
    // remove them from the ImsService.
    private Set<ImsRegistrationListenerProxy> mRegistrationListeners = new HashSet<>();

    // SystemProperties used as cache
    private static final String VOLTE_PROVISIONED_PROP = "net.lte.ims.volte.provisioned";
    private static final String WFC_PROVISIONED_PROP = "net.lte.ims.wfc.provisioned";
    private static final String VT_PROVISIONED_PROP = "net.lte.ims.vt.provisioned";
    // Flag indicating data enabled or not. This flag should be in sync with
    // DcTracker.isDataEnabled(). The flag will be set later during boot up.
    private static final String DATA_ENABLED_PROP = "net.lte.ims.data.enabled";

    public static final String TRUE = "true";
    public static final String FALSE = "false";

    // mRecentDisconnectReasons stores the last 16 disconnect reasons
    private static final int MAX_RECENT_DISCONNECT_REASONS = 16;
    private ConcurrentLinkedDeque<ImsReasonInfo> mRecentDisconnectReasons =
            new ConcurrentLinkedDeque<>();

    /**
     * Gets a manager instance.
     *
     * @param context application context for creating the manager object
     * @param phoneId the phone ID for the IMS Service
     * @return the manager instance corresponding to the phoneId
     */
    public static ImsManager getInstance(Context context, int phoneId) {
        synchronized (sImsManagerInstances) {
            if (sImsManagerInstances.containsKey(phoneId)) {
                ImsManager m = sImsManagerInstances.get(phoneId);
                // May be null for some tests
                if (m != null) {
                    m.connectIfServiceIsAvailable();
                }
                return m;
            }

            ImsManager mgr = new ImsManager(context, phoneId);
            sImsManagerInstances.put(phoneId, mgr);

            return mgr;
        }
    }

    /**
     * Returns the user configuration of Enhanced 4G LTE Mode setting.
     *
     * @deprecated Doesn't support MSIM devices. Use
     * {@link #isEnhanced4gLteModeSettingEnabledByUserForSlot} instead.
     */
    public static boolean isEnhanced4gLteModeSettingEnabledByUser(Context context) {
        // If user can't edit Enhanced 4G LTE Mode, it assumes Enhanced 4G LTE Mode is always true.
        // If user changes SIM from editable mode to uneditable mode, need to return true.
        if (!getBooleanCarrierConfig(context,
                    CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL)) {
            return true;
        }
        int enabled = android.provider.Settings.Global.getInt(
                context.getContentResolver(),
                android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED,
                ImsConfig.FeatureValueConstants.ON);
        return (enabled == 1) ? true : false;
    }

    /**
     * Returns the user configuration of Enhanced 4G LTE Mode setting for slot.
     */
    public boolean isEnhanced4gLteModeSettingEnabledByUserForSlot() {
        // If user can't edit Enhanced 4G LTE Mode, it assumes Enhanced 4G LTE Mode is always true.
        // If user changes SIM from editable mode to uneditable mode, need to return true.
        if (!getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL)) {
            return true;
        }
        int subId = getSubId(mPhoneId);
        log("isEnhanced4gLteModeSettingEnabledByUserForSlot :: subId=" + subId);
        int enabled = android.provider.Settings.Global.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED + subId,
                ImsConfig.FeatureValueConstants.ON);
        return (enabled == 1);
    }

    /**
     * Change persistent Enhanced 4G LTE Mode setting.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #setEnhanced4gLteModeSettingForSlot}
     * instead.
     */
    public static void setEnhanced4gLteModeSetting(Context context, boolean enabled) {
        int value = enabled ? 1 : 0;
        android.provider.Settings.Global.putInt(
                context.getContentResolver(),
                android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED, value);
        log("setEnhanced4gLteModeSetting ::");
        if (isNonTtyOrTtyOnVolteEnabled(context)) {
            ImsManager imsManager = ImsManager.getInstance(context,
                    SubscriptionManager.getDefaultVoicePhoneId());
            if (imsManager != null) {
                try {
                    imsManager.setAdvanced4GMode(enabled);
                } catch (ImsException ie) {
                    // do nothing
                }
            }
        }
    }

    /**
     * Change persistent Enhanced 4G LTE Mode setting. If the the option is not editable
     * ({@link CarrierConfigManager#KEY_EDITABLE_ENHANCED_4G_LTE_BOOL} is false), this method will
     * always set the setting to true.
     *
     */
    public void setEnhanced4gLteModeSettingForSlot(boolean enabled) {
        // If false, we must always keep advanced 4G mode set to true (1).
        int value = getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL) ? (enabled ? 1: 0) : 1;
        int subId = getSubId(mPhoneId);

        log("setEnhanced4gLteModeSettingForSlot :: subId=" + subId + " enabled=" + enabled);
        try {
            int prevSetting = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED + subId);
            if (prevSetting == value) {
                // Don't trigger setAdvanced4GMode if the setting hasn't changed.
                return;
            }
        } catch (Settings.SettingNotFoundException e) {
            // Setting doesn't exist yet, so set it below.
        }

        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED + subId, value);
        if (isNonTtyOrTtyOnVolteEnabledForSlot()) {
            try {
                setAdvanced4GMode(enabled);
            } catch (ImsException ie) {
                // do nothing
            }
        }
    }

    /**
     * Indicates whether the call is non-TTY or if TTY - whether TTY on VoLTE is
     * supported.
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isNonTtyOrTtyOnVolteEnabledForSlot} instead.
     */
    public static boolean isNonTtyOrTtyOnVolteEnabled(Context context) {
        if (getBooleanCarrierConfig(context,
                CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            return true;
        }

        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, TelecomManager.TTY_MODE_OFF)
                == TelecomManager.TTY_MODE_OFF;
    }

    /**
     * Indicates whether the call is non-TTY or if TTY - whether TTY on VoLTE is
     * supported on a per slot basis.
     */
    public boolean isNonTtyOrTtyOnVolteEnabledForSlot() {
        if (getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            return true;
        }

        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.PREFERRED_TTY_MODE, TelecomManager.TTY_MODE_OFF)
                == TelecomManager.TTY_MODE_OFF;
    }

    /**
     * Returns a platform configuration for VoLTE which may override the user setting.
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVolteEnabledByPlatformForSlot()} instead.
     */
    public static boolean isVolteEnabledByPlatform(Context context) {
        if (SystemProperties.getInt(PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE,
                PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return context.getResources().getBoolean(
                com.android.internal.R.bool.config_device_volte_available)
                && getBooleanCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
                && isGbaValid(context);
    }

    /**
     * Returns a platform configuration for VoLTE which may override the user setting on a per Slot
     * basis.
     */
    public boolean isVolteEnabledByPlatformForSlot() {
        if (SystemProperties.getInt(PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE,
                PROPERTY_DBG_VOLTE_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_volte_available)
                && getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
                && isGbaValidForSlot();
    }

    /**
     * Indicates whether VoLTE is provisioned on device.
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVolteProvisionedOnDeviceForSlot()} instead.
     */
    public static boolean isVolteProvisionedOnDevice(Context context) {
        if (getBooleanCarrierConfig(context,
                    CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            ImsManager mgr = ImsManager.getInstance(context,
                    SubscriptionManager.getDefaultVoicePhoneId());
            if (mgr != null) {
                return mgr.isVolteProvisioned();
            }
        }

        return true;
    }

    /**
     * Indicates whether VoLTE is provisioned on this slot.
     */
    public boolean isVolteProvisionedOnDeviceForSlot() {
        if (getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            return isVolteProvisioned();
        }

        return true;
    }

    /**
     * Indicates whether VoWifi is provisioned on device
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isWfcProvisionedOnDeviceForSlot()} instead.
     */
    public static boolean isWfcProvisionedOnDevice(Context context) {
        if (getBooleanCarrierConfig(context,
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            ImsManager mgr = ImsManager.getInstance(context,
                    SubscriptionManager.getDefaultVoicePhoneId());
            if (mgr != null) {
                return mgr.isWfcProvisioned();
            }
        }

        return true;
    }

    /**
     * Indicates whether VoWifi is provisioned on slot.
     */
    public boolean isWfcProvisionedOnDeviceForSlot() {
        if (getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
                return isWfcProvisioned();
        }

        return true;
    }

    /**
     * Indicates whether VT is provisioned on device
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVtProvisionedOnDeviceForSlot()} instead.
     */
    public static boolean isVtProvisionedOnDevice(Context context) {
        if (getBooleanCarrierConfig(context,
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            ImsManager mgr = ImsManager.getInstance(context,
                    SubscriptionManager.getDefaultVoicePhoneId());
            if (mgr != null) {
                return mgr.isVtProvisioned();
            }
        }

        return true;
    }

    /**
     * Indicates whether VT is provisioned on slot.
     */
    public boolean isVtProvisionedOnDeviceForSlot() {
        if (getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {
            return isVtProvisioned();
        }

        return true;
    }

    /**
     * Returns a platform configuration for VT which may override the user setting.
     *
     * Note: VT presumes that VoLTE is enabled (these are configuration settings
     * which must be done correctly).
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVtEnabledByPlatformForSlot()} instead.
     */
    public static boolean isVtEnabledByPlatform(Context context) {
        if (SystemProperties.getInt(PROPERTY_DBG_VT_AVAIL_OVERRIDE,
                PROPERTY_DBG_VT_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return
                context.getResources().getBoolean(
                        com.android.internal.R.bool.config_device_vt_available) &&
                getBooleanCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL) &&
                isGbaValid(context);
    }

    /**
     * Returns a platform configuration for VT which may override the user setting.
     *
     * Note: VT presumes that VoLTE is enabled (these are configuration settings
     * which must be done correctly).
     */
    public boolean isVtEnabledByPlatformForSlot() {
        if (SystemProperties.getInt(PROPERTY_DBG_VT_AVAIL_OVERRIDE,
                PROPERTY_DBG_VT_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_vt_available) &&
                getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL) &&
                isGbaValidForSlot();
    }

    /**
     * Returns the user configuration of VT setting
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isVtEnabledByUserForSlot()} instead.
     */
    public static boolean isVtEnabledByUser(Context context) {
        int enabled = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.VT_IMS_ENABLED,
                ImsConfig.FeatureValueConstants.ON);
        return (enabled == 1) ? true : false;
    }

    /**
     * Returns the user configuration of VT setting per slot.
     */
    public boolean isVtEnabledByUserForSlot() {
        int subId = getSubId(mPhoneId);
        log("isVtEnabledByUserForSlot :: subId=" + subId);
        int enabled = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.VT_IMS_ENABLED + subId,
                ImsConfig.FeatureValueConstants.ON);
        return (enabled == 1);
    }

    /**
     * Change persistent VT enabled setting
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #setVtSettingForSlot} instead.
     */
    public static void setVtSetting(Context context, boolean enabled) {
        int value = enabled ? 1 : 0;
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.VT_IMS_ENABLED, value);

        ImsManager imsManager = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (imsManager != null) {
            try {
                ImsConfig config = imsManager.getConfigInterface();
                config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE,
                        TelephonyManager.NETWORK_TYPE_LTE,
                        enabled ? ImsConfig.FeatureValueConstants.ON
                                : ImsConfig.FeatureValueConstants.OFF,
                        imsManager.mImsConfigListener);

                if (enabled) {
                    log("setVtSetting() : turnOnIms");
                    imsManager.turnOnIms();
                } else if (isTurnOffImsAllowedByPlatform(context)
                        && (!isVolteEnabledByPlatform(context)
                        || !isEnhanced4gLteModeSettingEnabledByUser(context))) {
                    log("setVtSetting() : imsServiceAllowTurnOff -> turnOffIms");
                    imsManager.turnOffIms();
                }
            } catch (ImsException e) {
                loge("setVtSetting(): ", e);
            }
        }
    }

    /**
     * Change persistent VT enabled setting for slot.
     */
    public void setVtSettingForSlot(boolean enabled) {
        int subId = getSubId(mPhoneId);
        log("setVtSettingForSlot :: subId=" + subId);
        int value = enabled ? 1 : 0;
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.VT_IMS_ENABLED + subId, value);

        try {
            ImsConfig config = getConfigInterface();
            config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE,
                    TelephonyManager.NETWORK_TYPE_LTE,
                    enabled ? ImsConfig.FeatureValueConstants.ON
                            : ImsConfig.FeatureValueConstants.OFF,
                    mImsConfigListener);

            if (enabled) {
                log("setVtSettingForSlot() : turnOnIms");
                turnOnIms();
            } else if (isVolteEnabledByPlatformForSlot()
                    && (!isVolteEnabledByPlatformForSlot()
                    || !isEnhanced4gLteModeSettingEnabledByUserForSlot())) {
                log("setVtSettingForSlot() : imsServiceAllowTurnOff -> turnOffIms");
                turnOffIms();
            }
        } catch (ImsException e) {
            loge("setVtSettingForSlot(): ", e);
        }
    }

    /**
     * Returns whether turning off ims is allowed by platform.
     * The platform property may override the carrier config.
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isTurnOffImsAllowedByPlatformForSlot} instead.
     */
    private static boolean isTurnOffImsAllowedByPlatform(Context context) {
        if (SystemProperties.getInt(PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE,
                PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE_DEFAULT) == 1) {
            return true;
        }
        return getBooleanCarrierConfig(context,
                CarrierConfigManager.KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL);
    }

    /**
     * Returns whether turning off ims is allowed by platform.
     * The platform property may override the carrier config.
     */
    private boolean isTurnOffImsAllowedByPlatformForSlot() {
        if (SystemProperties.getInt(PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE,
                PROPERTY_DBG_ALLOW_IMS_OFF_OVERRIDE_DEFAULT) == 1) {
            return true;
        }
        return getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_ALLOW_TURNOFF_IMS_BOOL);
    }

    /**
     * Returns the user configuration of WFC setting
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isTurnOffImsAllowedByPlatformForSlot} instead.
     */
    public static boolean isWfcEnabledByUser(Context context) {
        int enabled = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED,
                getBooleanCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);
        return (enabled == 1) ? true : false;
    }

    /**
     * Returns the user configuration of WFC setting for slot.
     */
    public boolean isWfcEnabledByUserForSlot() {
        int subId = getSubId(mPhoneId);
        int enabled = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED + subId,
                getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);
        log("isWfcEnabledByUserForSlot :: subId=" + subId + " enabled=" + enabled);
        return enabled == 1;
    }

    /**
     * Change persistent WFC enabled setting.
     * @deprecated Does not support MSIM devices. Please use
     * {@link #setWfcSettingForSlot} instead.
     */
    public static void setWfcSetting(Context context, boolean enabled) {
        int value = enabled ? 1 : 0;
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED, value);

        ImsManager imsManager = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (imsManager != null) {
            try {
                ImsConfig config = imsManager.getConfigInterface();
                config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI,
                        TelephonyManager.NETWORK_TYPE_IWLAN,
                        enabled ? ImsConfig.FeatureValueConstants.ON
                                : ImsConfig.FeatureValueConstants.OFF,
                        imsManager.mImsConfigListener);

                if (enabled) {
                    log("setWfcSetting() : turnOnIms");
                    imsManager.turnOnIms();
                } else if (isTurnOffImsAllowedByPlatform(context)
                        && (!isVolteEnabledByPlatform(context)
                        || !isEnhanced4gLteModeSettingEnabledByUser(context))) {
                    log("setWfcSetting() : imsServiceAllowTurnOff -> turnOffIms");
                    imsManager.turnOffIms();
                }

                TelephonyManager tm = (TelephonyManager) context
                        .getSystemService(Context.TELEPHONY_SERVICE);
                setWfcModeInternal(context, enabled
                        // Choose wfc mode per current roaming preference
                        ? getWfcMode(context, tm.isNetworkRoaming())
                        // Force IMS to register over LTE when turning off WFC
                        : ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED);
            } catch (ImsException e) {
                loge("setWfcSetting(): ", e);
            }
        }
    }

    /**
     * Change persistent WFC enabled setting for slot.
     */
    public void setWfcSettingForSlot(boolean enabled) {
        int value = enabled ? 1 : 0;
        int subId = getSubId(mPhoneId);
        final TelephonyManager telephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED + subId, value);
        log("setWfcSettingForSlot :: subId=" + subId + " enabled=" + enabled);
        setWfcNonPersistentForSlot(enabled, getWfcModeForSlot(
                telephonyManager.isNetworkRoaming(subId)));
    }

    /**
     * Non-persistently change WFC enabled setting and WFC mode for slot
     *
     * @param wfcMode The WFC preference if WFC is enabled
     */
    public void setWfcNonPersistentForSlot(boolean enabled, int wfcMode) {
        int imsFeatureValue =
                enabled ? ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF;
        // Force IMS to register over LTE when turning off WFC
        int imsWfcModeFeatureValue =
                enabled ? wfcMode : ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED;

        try {
            ImsConfig config = getConfigInterface();
            config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI,
                    TelephonyManager.NETWORK_TYPE_IWLAN,
                    imsFeatureValue,
                    mImsConfigListener);

            if (enabled) {
                log("setWfcSettingForSlot() : turnOnIms");
                turnOnIms();
            } else if (isTurnOffImsAllowedByPlatformForSlot()
                    && (!isVolteEnabledByPlatformForSlot()
                    || !isEnhanced4gLteModeSettingEnabledByUserForSlot())) {
                log("setWfcSettingForSlot() : imsServiceAllowTurnOff -> turnOffIms");
                turnOffIms();
            }

            setWfcModeInternalForSlot(imsWfcModeFeatureValue);
        } catch (ImsException e) {
            loge("setWfcSettingForSlot(): ", e);
        }
    }

    /**
     * Returns the user configuration of WFC preference setting.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #getWfcModeForSlot} instead.
     */
    public static int getWfcMode(Context context) {
        int setting = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_MODE, getIntCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));
        if (DBG) log("getWfcMode - setting=" + setting);
        return setting;
    }

    /**
     * Returns the user configuration of WFC preference setting
     */
    public int getWfcModeForSlot() {
        int subId = getSubId(mPhoneId);
        int setting = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_MODE + subId,
                        getIntCarrierConfigForSlot(
                                CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));
        log("getWfcModeForSlot :: subId=" + subId + " setting=" + setting);
        if (DBG) log("getWfcMode - setting=" + setting);
        return setting;
    }

    /**
     * Change persistent WFC preference setting.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #setWfcModeForSlot} instead.
     */
    public static void setWfcMode(Context context, int wfcMode) {
        if (DBG) log("setWfcMode - setting=" + wfcMode);
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_MODE, wfcMode);

        setWfcModeInternal(context, wfcMode);
    }

    /**
     * Change persistent WFC preference setting for slot.
     */
    public void setWfcModeForSlot(int wfcMode) {
        int subId = getSubId(mPhoneId);
        if (DBG) log("setWfcModeForSlot - setting=" + wfcMode + ", subId=" + subId);
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_MODE + subId, wfcMode);
        setWfcModeInternalForSlot(wfcMode);
    }

    public void updateDefaultWfcModeForSlot() {
        int subId = getSubId(mPhoneId);
        if (DBG) log("updateWfcModeForSlot");
        if (!getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL)) {
            android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_MODE + subId,
                    getIntCarrierConfigForSlot(
                    CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));
        }
    }

    /**
     * Returns the user configuration of WFC preference setting
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming  setting
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #getWfcModeForSlot} instead.
     */
    public static int getWfcMode(Context context, boolean roaming) {
        int setting = 0;
        if (!roaming) {
            setting = android.provider.Settings.Global.getInt(context.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_MODE, getIntCarrierConfig(context,
                            CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));
            if (DBG) log("getWfcMode - setting=" + setting);
        } else {
            setting = android.provider.Settings.Global.getInt(context.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_ROAMING_MODE,
                    getIntCarrierConfig(context,
                    CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT));
            if (DBG) log("getWfcMode (roaming) - setting=" + setting);
        }
        return setting;
    }

    /**
     * Returns the user configuration of WFC preference setting for slot
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming  setting
     */
    public int getWfcModeForSlot(boolean roaming) {
        int subId = getSubId(mPhoneId);
        log("getWfcModeForSlot :: subId=" + subId);
        int setting = 0;
        if (!roaming) {
            setting = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_MODE + subId,
                    getIntCarrierConfigForSlot(
                    CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));
            if (DBG) log("getWfcModeForSlot - setting=" + setting);
        } else {
            setting = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_ROAMING_MODE + subId,
                    getIntCarrierConfigForSlot(
                            CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT));
            if (DBG) log("getWfcModeForSlot (roaming) - setting=" + setting);
        }
        return setting;
    }

    /**
     * Change persistent WFC preference setting
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming setting
     *
     * @deprecated Doesn't support MSIM devices. Please use {@link #setWfcModeForSlot} instead.
     */
    public static void setWfcMode(Context context, int wfcMode, boolean roaming) {
        if (!roaming) {
            if (DBG) log("setWfcMode - setting=" + wfcMode);
            android.provider.Settings.Global.putInt(context.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_MODE, wfcMode);
        } else {
            if (DBG) log("setWfcMode (roaming) - setting=" + wfcMode);
            android.provider.Settings.Global.putInt(context.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_ROAMING_MODE, wfcMode);
        }

        TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        if (roaming == tm.isNetworkRoaming()) {
            setWfcModeInternal(context, wfcMode);
        }
    }

    /**
     * Change persistent WFC preference setting
     *
     * @param roaming {@code false} for home network setting, {@code true} for roaming setting
     */
    public void setWfcModeForSlot(int wfcMode, boolean roaming) {
        int subId = getSubId(mPhoneId);
        if (!roaming) {
            if (DBG) log("setWfcModeForSlot - setting=" + wfcMode);
            android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_MODE + subId, wfcMode);
        } else {
            if (DBG) log("setWfcModeForSlot (roaming) - setting=" + wfcMode);
            android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.WFC_IMS_ROAMING_MODE + subId, wfcMode);
        }

        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        if (roaming == tm.isNetworkRoaming(subId)) {
            setWfcModeInternalForSlot(wfcMode);
        }
    }

    private static void setWfcModeInternal(Context context, int wfcMode) {
        final ImsManager imsManager = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (imsManager != null) {
            final int value = wfcMode;
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        imsManager.getConfigInterface().setProvisionedValue(
                                ImsConfig.ConfigConstants.VOICE_OVER_WIFI_MODE,
                                value);
                    } catch (ImsException e) {
                        // do nothing
                    }
                }
            });
            thread.start();
        }
    }

    private void setWfcModeInternalForSlot(int wfcMode) {
        final int value = wfcMode;
        Thread thread = new Thread(() -> {
                try {
                    getConfigInterface().setProvisionedValue(
                            ImsConfig.ConfigConstants.VOICE_OVER_WIFI_MODE,
                            value);
                } catch (ImsException e) {
                    // do nothing
                }
        });
        thread.start();
    }

    /**
     * Returns the user configuration of WFC roaming setting
     *
     * @deprecated Does not support MSIM devices. Please use
     * {@link #isWfcRoamingEnabledByUserForSlot} instead.
     */
    public static boolean isWfcRoamingEnabledByUser(Context context) {
        int enabled = android.provider.Settings.Global.getInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ROAMING_ENABLED,
                getBooleanCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);
        return (enabled == 1) ? true : false;
    }

    /**
     * Returns the user configuration of WFC roaming setting for slot
     */
    public boolean isWfcRoamingEnabledByUserForSlot() {
        int subId = getSubId(mPhoneId);
        int enabled = android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ROAMING_ENABLED + subId,
                getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);
        log("isWfcRoamingEnabledByUserForSlot :: subId=" + subId + " enabled=" + (enabled == 1));
        return (enabled == 1);
    }

    /**
     * Change persistent WFC roaming enabled setting
     */
    public static void setWfcRoamingSetting(Context context, boolean enabled) {
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ROAMING_ENABLED,
                enabled ? ImsConfig.FeatureValueConstants.ON
                        : ImsConfig.FeatureValueConstants.OFF);

        final ImsManager imsManager = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());
        if (imsManager != null) {
            imsManager.setWfcRoamingSettingInternal(enabled);
        }
    }

    /**
     * Change persistent WFC roaming enabled setting
     */
    public void setWfcRoamingSettingForSlot(boolean enabled) {
        int subId = getSubId(mPhoneId);
        log("isWfcRoamingEnabledByUserForSlot :: subId=" + subId + " enabled=" + enabled);
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ROAMING_ENABLED + subId,
                enabled ? ImsConfig.FeatureValueConstants.ON
                        : ImsConfig.FeatureValueConstants.OFF);

        setWfcRoamingSettingInternal(enabled);
    }

    private void setWfcRoamingSettingInternal(boolean enabled) {
        final int value = enabled
                ? ImsConfig.FeatureValueConstants.ON
                : ImsConfig.FeatureValueConstants.OFF;
        Thread thread = new Thread(() -> {
                try {
                    getConfigInterface().setProvisionedValue(
                            ImsConfig.ConfigConstants.VOICE_OVER_WIFI_ROAMING,
                            value);
                } catch (ImsException e) {
                    // do nothing
                }
        });
        thread.start();
    }

    /**
     * Returns a platform configuration for WFC which may override the user
     * setting. Note: WFC presumes that VoLTE is enabled (these are
     * configuration settings which must be done correctly).
     *
     * @deprecated Doesn't work for MSIM devices. Use {@link #isWfcEnabledByPlatformForSlot}
     * instead.
     */
    public static boolean isWfcEnabledByPlatform(Context context) {
        if (SystemProperties.getInt(PROPERTY_DBG_WFC_AVAIL_OVERRIDE,
                PROPERTY_DBG_WFC_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return
               context.getResources().getBoolean(
                       com.android.internal.R.bool.config_device_wfc_ims_available) &&
               getBooleanCarrierConfig(context,
                       CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL) &&
               isGbaValid(context);
    }

    /**
     * Returns a platform configuration for WFC which may override the user
     * setting per slot. Note: WFC presumes that VoLTE is enabled (these are
     * configuration settings which must be done correctly).
     */
    public boolean isWfcEnabledByPlatformForSlot() {
        if (SystemProperties.getInt(PROPERTY_DBG_WFC_AVAIL_OVERRIDE,
                PROPERTY_DBG_WFC_AVAIL_OVERRIDE_DEFAULT) == 1) {
            return true;
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_wfc_ims_available) &&
                getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL) &&
                isGbaValidForSlot();
    }

    /**
     * If carrier requires that IMS is only available if GBA capable SIM is used,
     * then this function checks GBA bit in EF IST.
     *
     * Format of EF IST is defined in 3GPP TS 31.103 (Section 4.2.7).
     *
     * @deprecated Use {@link #isGbaValidForSlot} instead
     */
    private static boolean isGbaValid(Context context) {
        if (getBooleanCarrierConfig(context,
                CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)) {
            final TelephonyManager telephonyManager = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            String efIst = telephonyManager.getIsimIst();
            if (efIst == null) {
                loge("ISF is NULL");
                return true;
            }
            boolean result = efIst != null && efIst.length() > 1 &&
                    (0x02 & (byte)efIst.charAt(1)) != 0;
            if (DBG) log("GBA capable=" + result + ", ISF=" + efIst);
            return result;
        }
        return true;
    }

    /**
     * If carrier requires that IMS is only available if GBA capable SIM is used,
     * then this function checks GBA bit in EF IST.
     *
     * Format of EF IST is defined in 3GPP TS 31.103 (Section 4.2.7).
     */
    private boolean isGbaValidForSlot() {
        if (getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)) {
            final TelephonyManager telephonyManager = (TelephonyManager)
                    mContext.getSystemService(Context.TELEPHONY_SERVICE);
            String efIst = telephonyManager.getIsimIst();
            if (efIst == null) {
                loge("isGbaValidForSlot - ISF is NULL");
                return true;
            }
            boolean result = efIst != null && efIst.length() > 1 &&
                    (0x02 & (byte)efIst.charAt(1)) != 0;
            if (DBG) log("isGbaValidForSlot - GBA capable=" + result + ", ISF=" + efIst);
            return result;
        }
        return true;
    }

    /**
     * This function should be called when ImsConfig.ACTION_IMS_CONFIG_CHANGED is received.
     *
     * We cannot register receiver in ImsManager because this would lead to resource leak.
     * ImsManager can be created in different processes and it is not notified when that process
     * is about to be terminated.
     *
     * @hide
     * */
    public static void onProvisionedValueChanged(Context context, int item, String value) {
        if (DBG) Rlog.d(TAG, "onProvisionedValueChanged: item=" + item + " val=" + value);
        ImsManager mgr = ImsManager.getInstance(context,
                SubscriptionManager.getDefaultVoicePhoneId());

        switch (item) {
            case ImsConfig.ConfigConstants.VLT_SETTING_ENABLED:
                mgr.setVolteProvisionedProperty(value.equals("1"));
                if (DBG) Rlog.d(TAG,"isVoLteProvisioned = " + mgr.isVolteProvisioned());
                break;

            case ImsConfig.ConfigConstants.VOICE_OVER_WIFI_SETTING_ENABLED:
                mgr.setWfcProvisionedProperty(value.equals("1"));
                if (DBG) Rlog.d(TAG,"isWfcProvisioned = " + mgr.isWfcProvisioned());
                break;

            case ImsConfig.ConfigConstants.LVC_SETTING_ENABLED:
                mgr.setVtProvisionedProperty(value.equals("1"));
                if (DBG) Rlog.d(TAG,"isVtProvisioned = " + mgr.isVtProvisioned());
                break;

        }
    }

    private class AsyncUpdateProvisionedValues extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            // disable on any error
            setVolteProvisionedProperty(false);
            setWfcProvisionedProperty(false);
            setVtProvisionedProperty(false);

            try {
                ImsConfig config = getConfigInterface();
                if (config != null) {
                    setVolteProvisionedProperty(getProvisionedBool(config,
                            ImsConfig.ConfigConstants.VLT_SETTING_ENABLED));
                    if (DBG) Rlog.d(TAG, "isVoLteProvisioned = " + isVolteProvisioned());

                    setWfcProvisionedProperty(getProvisionedBool(config,
                            ImsConfig.ConfigConstants.VOICE_OVER_WIFI_SETTING_ENABLED));
                    if (DBG) Rlog.d(TAG, "isWfcProvisioned = " + isWfcProvisioned());

                    setVtProvisionedProperty(getProvisionedBool(config,
                            ImsConfig.ConfigConstants.LVC_SETTING_ENABLED));
                    if (DBG) Rlog.d(TAG, "isVtProvisioned = " + isVtProvisioned());

                }
            } catch (ImsException ie) {
                Rlog.e(TAG, "AsyncUpdateProvisionedValues error: ", ie);
            }

            return null;
        }

        private boolean getProvisionedBool(ImsConfig config, int item) throws ImsException {
            return config.getProvisionedValue(item) == ImsConfig.FeatureValueConstants.ON;
        }
    }

    /** Asynchronously get VoLTE, WFC, VT provisioning statuses */
    private void updateProvisionedValues() {
        if (getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL)) {

            new AsyncUpdateProvisionedValues().execute();
        }
    }

    /**
     * Sync carrier config and user settings with ImsConfig.
     *
     * @param context for the manager object
     * @param phoneId phone id
     * @param force update
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #updateImsServiceConfigForSlot} instead.
     */
    public static void updateImsServiceConfig(Context context, int phoneId, boolean force) {
        if (!force) {
            final TelephonyManager telephonyManager = (TelephonyManager)
                    context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager.getSimState(phoneId) != TelephonyManager.SIM_STATE_READY) {
                log("updateImsServiceConfig: SIM not ready");
                // Don't disable IMS if SIM is not ready
                return;
            }
        }

        final ImsManager imsManager = ImsManager.getInstance(context, phoneId);
        if (imsManager != null && (!imsManager.mConfigUpdated || force)) {
            try {
                imsManager.updateProvisionedValues();

                // TODO: Extend ImsConfig API and set all feature values in single function call.

                // Note: currently the order of updates is set to produce different order of
                // setFeatureValue() function calls from setAdvanced4GMode(). This is done to
                // differentiate this code path from vendor code perspective.
                boolean isImsUsed = imsManager.updateVolteFeatureValue();
                isImsUsed |= imsManager.updateWfcFeatureAndProvisionedValues();
                isImsUsed |= imsManager.updateVideoCallFeatureValue();

                if (isImsUsed || !isTurnOffImsAllowedByPlatform(context)) {
                    // Turn on IMS if it is used.
                    // Also, if turning off is not allowed for current carrier,
                    // we need to turn IMS on because it might be turned off before
                    // phone switched to current carrier.
                    log("updateImsServiceConfig: turnOnIms");
                    imsManager.turnOnIms();
                } else {
                    // Turn off IMS if it is not used AND turning off is allowed for carrier.
                    log("updateImsServiceConfig: turnOffIms");
                    imsManager.turnOffIms();
                }

                imsManager.mConfigUpdated = true;
            } catch (ImsException e) {
                loge("updateImsServiceConfig: ", e);
                imsManager.mConfigUpdated = false;
            }
        }
    }

    /**
     * Sync carrier config and user settings with ImsConfig.
     *
     * @param context for the manager object
     * @param phoneId phone id
     * @param force update
     */
    public void updateImsServiceConfigForSlot(boolean force) {
        if (!force) {
            final TelephonyManager telephonyManager = (TelephonyManager)
                    mContext.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager.getSimState(mPhoneId)
                    != TelephonyManager.SIM_STATE_READY) {
                log("updateImsServiceConfigForSlot: SIM not ready");
                // Don't disable IMS if SIM is not ready
                return;
            }
        }

        int subId = getSubId(mPhoneId);

        if (!SubscriptionManager.from(mContext).isActiveSubId(subId)) {
            log("updateImsServiceConfigForSlot: subId not active: " + subId);
            return;
        }

        if (!mConfigUpdated || force) {
            try {
                updateProvisionedValues();

                // TODO: Extend ImsConfig API and set all feature values in single function call.

                // Note: currently the order of updates is set to produce different order of
                // setFeatureValue() function calls from setAdvanced4GMode(). This is done to
                // differentiate this code path from vendor code perspective.
                boolean isImsUsed = updateVolteFeatureValue();
                isImsUsed |= updateWfcFeatureAndProvisionedValues();
                isImsUsed |= updateVideoCallFeatureValue();

                if (isImsUsed || !isTurnOffImsAllowedByPlatformForSlot()) {
                    // Turn on IMS if it is used.
                    // Also, if turning off is not allowed for current carrier,
                    // we need to turn IMS on because it might be turned off before
                    // phone switched to current carrier.
                    log("updateImsServiceConfigForSlot: turnOnIms");
                    turnOnIms();
                } else {
                    // Turn off IMS if it is not used AND turning off is allowed for carrier.
                    log("updateImsServiceConfigForSlot: turnOffIms");
                    turnOffIms();
                }

                mConfigUpdated = true;
            } catch (ImsException e) {
                loge("updateImsServiceConfigForSlot: ", e);
                mConfigUpdated = false;
            }
        }
    }

    /**
     * Update VoLTE config
     * @return whether feature is On
     * @throws ImsException
     */
    private boolean updateVolteFeatureValue() throws ImsException {
        boolean available = isVolteEnabledByPlatformForSlot();
        boolean enabled = isEnhanced4gLteModeSettingEnabledByUserForSlot();
        boolean isNonTty = isNonTtyOrTtyOnVolteEnabledForSlot();
        boolean isFeatureOn = available && enabled && isNonTty;

        log("updateVolteFeatureValue: available = " + available
                + ", enabled = " + enabled
                + ", nonTTY = " + isNonTty);

        getConfigInterface().setFeatureValue(
                ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE,
                TelephonyManager.NETWORK_TYPE_LTE,
                isFeatureOn ?
                        ImsConfig.FeatureValueConstants.ON :
                        ImsConfig.FeatureValueConstants.OFF,
                mImsConfigListener);

        return isFeatureOn;
    }

    /**
     * Update video call over LTE config
     * @return whether feature is On
     * @throws ImsException
     */
    private boolean updateVideoCallFeatureValue() throws ImsException {
        boolean available = isVtEnabledByPlatformForSlot();
        boolean enabled = isVtEnabledByUserForSlot();
        boolean isNonTty = isNonTtyOrTtyOnVolteEnabledForSlot();
        boolean isDataEnabled = isDataEnabled();
        boolean ignoreDataEnabledChanged = getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS);

        boolean isFeatureOn = available && enabled && isNonTty
                && (ignoreDataEnabledChanged || isDataEnabled);

        log("updateVideoCallFeatureValue: available = " + available
                + ", enabled = " + enabled
                + ", nonTTY = " + isNonTty
                + ", data enabled = " + isDataEnabled);

        getConfigInterface().setFeatureValue(
                ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE,
                TelephonyManager.NETWORK_TYPE_LTE,
                isFeatureOn ?
                        ImsConfig.FeatureValueConstants.ON :
                        ImsConfig.FeatureValueConstants.OFF,
                mImsConfigListener);

        return isFeatureOn;
    }

    /**
     * Update WFC config
     * @return whether feature is On
     * @throws ImsException
     */
    private boolean updateWfcFeatureAndProvisionedValues() throws ImsException {
        boolean roaming = isWfcRoamingEnabledByUserForSlot();
        boolean available = isWfcEnabledByPlatformForSlot();
        boolean enabled = isWfcEnabledByUserForSlot();
        final int subId = getSubId(mPhoneId);
        final TelephonyManager telephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        boolean isNetworkRoaming = telephonyManager.isNetworkRoaming(subId);
        updateDefaultWfcModeForSlot();
        int mode = getWfcModeForSlot(isNetworkRoaming);
        boolean isFeatureOn = available && enabled;

        log("updateWfcFeatureAndProvisionedValues: available = " + available
                + ", enabled = " + enabled
                + ", mode = " + mode
                + ", roaming = " + roaming
                + ", isNetworkRoaming = " + isNetworkRoaming);

        getConfigInterface().setFeatureValue(
                ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_WIFI,
                TelephonyManager.NETWORK_TYPE_IWLAN,
                isFeatureOn ?
                        ImsConfig.FeatureValueConstants.ON :
                        ImsConfig.FeatureValueConstants.OFF,
                mImsConfigListener);

        if (!isFeatureOn) {
            mode = ImsConfig.WfcModeFeatureValueConstants.CELLULAR_PREFERRED;
            roaming = false;
        }
        setWfcModeInternalForSlot(mode);
        setWfcRoamingSettingInternal(roaming);

        return isFeatureOn;
    }

    /**
     * Do NOT use this directly, instead use {@link #getInstance}.
     */
    @VisibleForTesting
    public ImsManager(Context context, int phoneId) {
        mContext = context;
        mPhoneId = phoneId;
        mConfigDynamicBind = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dynamic_bind_ims);
        mConfigManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        createImsService();
    }

    /**
     * @return Whether or not ImsManager is configured to Dynamically bind or not to support legacy
     * devices.
     */
    public boolean isDynamicBinding() {
        return mConfigDynamicBind;
    }

    /*
     * Returns a flag indicating whether the IMS service is available. If it is not available,
     * it will try to connect before reporting failure.
     */
    public boolean isServiceAvailable() {
        connectIfServiceIsAvailable();
        // mImsServiceProxy will always create an ImsServiceProxy.
        return mImsServiceProxy.isBinderAlive();
    }

    /**
     * If the service is available, try to reconnect.
     */
    public void connectIfServiceIsAvailable() {
        if (mImsServiceProxy == null || !mImsServiceProxy.isBinderAlive()) {
            createImsService();
        }
    }

    public void setImsConfigListener(ImsConfigListener listener) {
        mImsConfigListener = listener;
    }


    /**
     * Adds a callback for status changed events if the binder is already available. If it is not,
     * this method will throw an ImsException.
     */
    public void addNotifyStatusChangedCallbackIfAvailable(ImsServiceProxy.INotifyStatusChanged c)
            throws ImsException {
        if (!mImsServiceProxy.isBinderAlive()) {
            throw new ImsException("Binder is not active!",
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
        if (c != null) {
            mStatusCallbacks.add(c);
        }
    }

    /**
     * Opens the IMS service for making calls and/or receiving generic IMS calls.
     * The caller may make subsquent calls through {@link #makeCall}.
     * The IMS service will register the device to the operator's network with the credentials
     * (from ISIM) periodically in order to receive calls from the operator's network.
     * When the IMS service receives a new call, it will send out an intent with
     * the provided action string.
     * The intent contains a call ID extra {@link getCallId} and it can be used to take a call.
     *
     * @param serviceClass a service class specified in {@link ImsServiceClass}
     *      For VoLTE service, it MUST be a {@link ImsServiceClass#MMTEL}.
     * @param incomingCallPendingIntent When an incoming call is received,
     *        the IMS service will call {@link PendingIntent#send(Context, int, Intent)} to
     *        send back the intent to the caller with {@link #INCOMING_CALL_RESULT_CODE}
     *        as the result code and the intent to fill in the call ID; It cannot be null
     * @param listener To listen to IMS registration events; It cannot be null
     * @return identifier (greater than 0) for the specified service
     * @throws NullPointerException if {@code incomingCallPendingIntent}
     *      or {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     * @see #getCallId
     * @see #getImsSessionId
     */
    public int open(int serviceClass, PendingIntent incomingCallPendingIntent,
            ImsConnectionStateListener listener) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        if (incomingCallPendingIntent == null) {
            throw new NullPointerException("incomingCallPendingIntent can't be null");
        }

        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }

        int result = 0;

        try {
            result = mImsServiceProxy.startSession(incomingCallPendingIntent,
                    createRegistrationListenerProxy(serviceClass, listener));
        } catch (RemoteException e) {
            throw new ImsException("open()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }

        if (result <= 0) {
            // If the return value is a minus value,
            // it means that an error occurred in the service.
            // So, it needs to convert to the reason code specified in ImsReasonInfo.
            throw new ImsException("open()", (result * (-1)));
        }

        return result;
    }

    /**
     * Adds registration listener to the IMS service.
     *
     * @param serviceClass a service class specified in {@link ImsServiceClass}
     *      For VoLTE service, it MUST be a {@link ImsServiceClass#MMTEL}.
     * @param listener To listen to IMS registration events; It cannot be null
     * @throws NullPointerException if {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     */
    public void addRegistrationListener(int serviceClass, ImsConnectionStateListener listener)
            throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }

        try {
            ImsRegistrationListenerProxy p = createRegistrationListenerProxy(serviceClass,
                    listener);
            mRegistrationListeners.add(p);
            mImsServiceProxy.addRegistrationListener(p);
        } catch (RemoteException e) {
            throw new ImsException("addRegistrationListener()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Removes the registration listener from the IMS service.
     *
     * @param listener Previously registered listener that will be removed. Can not be null.
     * @throws NullPointerException if {@code listener} is null
     * @throws ImsException if calling the IMS service results in an error
     * instead.
     */
    public void removeRegistrationListener(ImsConnectionStateListener listener)
            throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }

        try {
            Optional<ImsRegistrationListenerProxy> optionalProxy = mRegistrationListeners.stream()
                    .filter(l -> listener.equals(l.mListener)).findFirst();
            if(optionalProxy.isPresent()) {
                ImsRegistrationListenerProxy p = optionalProxy.get();
                mRegistrationListeners.remove(p);
                mImsServiceProxy.removeRegistrationListener(p);
            }
        } catch (RemoteException e) {
            throw new ImsException("removeRegistrationListener()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Closes the specified service ({@link ImsServiceClass}) not to make/receive calls.
     * All the resources that were allocated to the service are also released.
     *
     * @param sessionId a session id to be closed which is obtained from {@link ImsManager#open}
     * @throws ImsException if calling the IMS service results in an error
     */
    public void close(int sessionId) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsServiceProxy.endSession(sessionId);
        } catch (RemoteException e) {
            throw new ImsException("close()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        } finally {
            mUt = null;
            mConfig = null;
            mEcbm = null;
            mMultiEndpoint = null;
        }
    }

    /**
     * Gets the configuration interface to provision / withdraw the supplementary service settings.
     *
     * @return the Ut interface instance
     * @throws ImsException if getting the Ut interface results in an error
     */
    public ImsUtInterface getSupplementaryServiceConfiguration()
            throws ImsException {
        // FIXME: manage the multiple Ut interfaces based on the session id
        if (mUt == null || !mImsServiceProxy.isBinderAlive()) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsUt iUt = mImsServiceProxy.getUtInterface();

                if (iUt == null) {
                    throw new ImsException("getSupplementaryServiceConfiguration()",
                            ImsReasonInfo.CODE_UT_NOT_SUPPORTED);
                }

                mUt = new ImsUt(iUt);
            } catch (RemoteException e) {
                throw new ImsException("getSupplementaryServiceConfiguration()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }

        return mUt;
    }

    /**
     * Checks if the IMS service has successfully registered to the IMS network
     * with the specified service & call type.
     *
     * @param serviceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE_N_VIDEO}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     * @return true if the specified service id is connected to the IMS network;
     *        false otherwise
     * @throws ImsException if calling the IMS service results in an error
     */
    public boolean isConnected(int serviceType, int callType)
            throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mImsServiceProxy.isConnected(serviceType, callType);
        } catch (RemoteException e) {
            throw new ImsException("isServiceConnected()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Checks if the specified IMS service is opend.
     *
     * @return true if the specified service id is opened; false otherwise
     * @throws ImsException if calling the IMS service results in an error
     */
    public boolean isOpened() throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mImsServiceProxy.isOpened();
        } catch (RemoteException e) {
            throw new ImsException("isOpened()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Creates a {@link ImsCallProfile} from the service capabilities & IMS registration state.
     *
     * @param sessionId a session id which is obtained from {@link ImsManager#open}
     * @param serviceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NONE}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VT_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_RX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_NODIR}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     *        {@link ImsCallProfile#CALL_TYPE_VS_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VS_RX}
     * @return a {@link ImsCallProfile} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCallProfile createCallProfile(int sessionId, int serviceType, int callType)
            throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            return mImsServiceProxy.createCallProfile(sessionId, serviceType, callType);
        } catch (RemoteException e) {
            throw new ImsException("createCallProfile()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    /**
     * Creates a {@link ImsCall} to make a call.
     *
     * @param sessionId a session id which is obtained from {@link ImsManager#open}
     * @param profile a call profile to make the call
     *      (it contains service type, call type, media information, etc.)
     * @param participants participants to invite the conference call
     * @param listener listen to the call events from {@link ImsCall}
     * @return a {@link ImsCall} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCall makeCall(int sessionId, ImsCallProfile profile, String[] callees,
            ImsCall.Listener listener) throws ImsException {
        if (DBG) {
            log("makeCall :: sessionId=" + sessionId
                    + ", profile=" + profile);
        }

        checkAndThrowExceptionIfServiceUnavailable();

        ImsCall call = new ImsCall(mContext, profile);

        call.setListener(listener);
        ImsCallSession session = createCallSession(sessionId, profile);
        boolean isConferenceUri = profile.getCallExtraBoolean(
                TelephonyProperties.EXTRAS_IS_CONFERENCE_URI, false);
        if (!isConferenceUri && (callees != null) && (callees.length == 1)) {
            call.start(session, callees[0]);
        } else {
            call.start(session, callees);
        }

        return call;
    }

    /**
     * Creates a {@link ImsCall} to take an incoming call.
     *
     * @param sessionId a session id which is obtained from {@link ImsManager#open}
     * @param incomingCallIntent the incoming call broadcast intent
     * @param listener to listen to the call events from {@link ImsCall}
     * @return a {@link ImsCall} object
     * @throws ImsException if calling the IMS service results in an error
     */
    public ImsCall takeCall(int sessionId, Intent incomingCallIntent,
            ImsCall.Listener listener) throws ImsException {
        if (DBG) {
            log("takeCall :: sessionId=" + sessionId
                    + ", incomingCall=" + incomingCallIntent);
        }

        checkAndThrowExceptionIfServiceUnavailable();

        if (incomingCallIntent == null) {
            throw new ImsException("Can't retrieve session with null intent",
                    ImsReasonInfo.CODE_LOCAL_ILLEGAL_ARGUMENT);
        }

        int incomingServiceId = getImsSessionId(incomingCallIntent);

        if (sessionId != incomingServiceId) {
            throw new ImsException("Service id is mismatched in the incoming call intent",
                    ImsReasonInfo.CODE_LOCAL_ILLEGAL_ARGUMENT);
        }

        String callId = getCallId(incomingCallIntent);

        if (callId == null) {
            throw new ImsException("Call ID missing in the incoming call intent",
                    ImsReasonInfo.CODE_LOCAL_ILLEGAL_ARGUMENT);
        }

        try {
            IImsCallSession session = mImsServiceProxy.getPendingCallSession(sessionId, callId);

            if (session == null) {
                throw new ImsException("No pending session for the call",
                        ImsReasonInfo.CODE_LOCAL_NO_PENDING_CALL);
            }

            ImsCall call = new ImsCall(mContext, session.getCallProfile());

            call.attachSession(new ImsCallSession(session));
            call.setListener(listener);

            return call;
        } catch (Throwable t) {
            throw new ImsException("takeCall()", t, ImsReasonInfo.CODE_UNSPECIFIED);
        }
    }

    /**
     * Gets the config interface to get/set service/capability parameters.
     *
     * @return the ImsConfig instance.
     * @throws ImsException if getting the setting interface results in an error.
     */
    public ImsConfig getConfigInterface() throws ImsException {

        if (mConfig == null || !mImsServiceProxy.isBinderAlive()) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsConfig config = mImsServiceProxy.getConfigInterface();
                if (config == null) {
                    throw new ImsException("getConfigInterface()",
                            ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
                }
                mConfig = new ImsConfig(config, mContext);
            } catch (RemoteException e) {
                throw new ImsException("getConfigInterface()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
        if (DBG) log("getConfigInterface(), mConfig= " + mConfig);
        return mConfig;
    }

    public void setUiTTYMode(Context context, int uiTtyMode, Message onComplete)
            throws ImsException {

        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsServiceProxy.setUiTTYMode(uiTtyMode, onComplete);
        } catch (RemoteException e) {
            throw new ImsException("setTTYMode()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }

        if (!getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL)) {
            setAdvanced4GMode((uiTtyMode == TelecomManager.TTY_MODE_OFF) &&
                    isEnhanced4gLteModeSettingEnabledByUserForSlot());
        }
    }

    private ImsReasonInfo makeACopy(ImsReasonInfo imsReasonInfo) {
        Parcel p = Parcel.obtain();
        imsReasonInfo.writeToParcel(p, 0);
        p.setDataPosition(0);
        ImsReasonInfo clonedReasonInfo = ImsReasonInfo.CREATOR.createFromParcel(p);
        p.recycle();
        return clonedReasonInfo;
    }

    /**
     * Get Recent IMS Disconnect Reasons.
     *
     * @return ArrayList of ImsReasonInfo objects. MAX size of the arraylist
     * is MAX_RECENT_DISCONNECT_REASONS. The objects are in the
     * chronological order.
     */
    public ArrayList<ImsReasonInfo> getRecentImsDisconnectReasons() {
        ArrayList<ImsReasonInfo> disconnectReasons = new ArrayList<>();

        for (ImsReasonInfo reason : mRecentDisconnectReasons) {
            disconnectReasons.add(makeACopy(reason));
        }
        return disconnectReasons;
    }

    public int getImsServiceStatus() throws ImsException {
        return mImsServiceProxy.getFeatureStatus();
    }

    /**
     * Get the boolean config from carrier config manager.
     *
     * @param context the context to get carrier service
     * @param key config key defined in CarrierConfigManager
     * @return boolean value of corresponding key.
     *
     * @deprecated Does not support MSIM devices. Use
     * {@link #getBooleanCarrierConfigForSlot(Context, String)} instead.
     */
    private static boolean getBooleanCarrierConfig(Context context, String key) {
        CarrierConfigManager configManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = null;
        if (configManager != null) {
            b = configManager.getConfig();
        }
        if (b != null) {
            return b.getBoolean(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getBoolean(key);
        }
    }

    /**
     * Get the boolean config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return boolean value of corresponding key.
     */
    private boolean getBooleanCarrierConfigForSlot(String key) {
        int subId = getSubId(mPhoneId);
        PersistableBundle b = null;
        if (mConfigManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = mConfigManager.getConfigForSubId(subId);
        }
        if (b != null) {
            return b.getBoolean(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getBoolean(key);
        }
    }

    /**
     * Get the int config from carrier config manager.
     *
     * @param context the context to get carrier service
     * @param key config key defined in CarrierConfigManager
     * @return integer value of corresponding key.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #getIntCarrierConfigForSlot} instead.
     */
    private static int getIntCarrierConfig(Context context, String key) {
        CarrierConfigManager configManager = (CarrierConfigManager) context.getSystemService(
                Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle b = null;
        if (configManager != null) {
            b = configManager.getConfig();
        }
        if (b != null) {
            return b.getInt(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getInt(key);
        }
    }

    /**
     * Get the int config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return integer value of corresponding key.
     */
    private int getIntCarrierConfigForSlot(String key) {
        int subId = getSubId(mPhoneId);
        PersistableBundle b = null;
        if (mConfigManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = mConfigManager.getConfigForSubId(subId);
        }
        if (b != null) {
            return b.getInt(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getInt(key);
        }
    }

    /**
     * Gets the call ID from the specified incoming call broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the call ID or null if the intent does not contain it
     */
    private static String getCallId(Intent incomingCallIntent) {
        if (incomingCallIntent == null) {
            return null;
        }

        return incomingCallIntent.getStringExtra(EXTRA_CALL_ID);
    }

    /**
     * Gets the service type from the specified incoming call broadcast intent.
     *
     * @param incomingCallIntent the incoming call broadcast intent
     * @return the session identifier or -1 if the intent does not contain it
     */
    private static int getImsSessionId(Intent incomingCallIntent) {
        if (incomingCallIntent == null) {
            return (-1);
        }

        return incomingCallIntent.getIntExtra(EXTRA_SERVICE_ID, -1);
    }

    /**
     * Checks to see if the ImsService Binder is connected. If it is not, we try to create the
     * connection again.
     */
    private void checkAndThrowExceptionIfServiceUnavailable()
            throws ImsException {
        if (mImsServiceProxy == null || !mImsServiceProxy.isBinderAlive()) {
            createImsService();

            if (mImsServiceProxy == null) {
                throw new ImsException("Service is unavailable",
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
    }

    /**
     * Binds the IMS service to make/receive the call. Supports two methods of exposing an
     * ImsService:
     * 1) com.android.ims.ImsService implementation in ServiceManager (deprecated).
     * 2) android.telephony.ims.ImsService implementation through ImsResolver.
     */
    private void createImsService() {
        if (!mConfigDynamicBind) {
            // Old method of binding
            Rlog.i(TAG, "Creating ImsService using ServiceManager");
            mImsServiceProxy = getServiceProxyCompat();
        } else {
            Rlog.i(TAG, "Creating ImsService using ImsResolver");
            mImsServiceProxy = getServiceProxy();
        }
    }

    // Deprecated method of binding with the ImsService defined in the ServiceManager.
    private ImsServiceProxyCompat getServiceProxyCompat() {
        IBinder binder = ServiceManager.checkService(IMS_SERVICE);

        if (binder != null) {
            try {
                binder.linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
            }
        }

        return new ImsServiceProxyCompat(mPhoneId, binder);
    }

    // New method of binding with the ImsResolver
    private ImsServiceProxy getServiceProxy() {
        TelephonyManager tm = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        ImsServiceProxy serviceProxy = new ImsServiceProxy(mPhoneId, ImsFeature.MMTEL);
        serviceProxy.setStatusCallback(() ->  mStatusCallbacks.forEach(
                ImsServiceProxy.INotifyStatusChanged::notifyStatusChanged));
        // Returns null if the service is not available.
        IImsServiceController b = tm.getImsServiceControllerAndListen(mPhoneId,
                ImsFeature.MMTEL, serviceProxy.getListener());
        if (b != null) {
            serviceProxy.setBinder(b.asBinder());
            // Trigger the cache to be updated for feature status.
            serviceProxy.getFeatureStatus();
        } else {
            Rlog.w(TAG, "getServiceProxy: b is null! Phone Id: " + mPhoneId);
        }
        return serviceProxy;
    }

    /**
     * Creates a {@link ImsCallSession} with the specified call profile.
     * Use other methods, if applicable, instead of interacting with
     * {@link ImsCallSession} directly.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @param profile a call profile to make the call
     */
    private ImsCallSession createCallSession(int serviceId,
            ImsCallProfile profile) throws ImsException {
        try {
            // Throws an exception if the ImsService Feature is not ready to accept commands.
            return new ImsCallSession(mImsServiceProxy.createCallSession(serviceId, profile, null));
        } catch (RemoteException e) {
            Rlog.w(TAG, "CreateCallSession: Error, remote exception: " + e.getMessage());
            throw new ImsException("createCallSession()", e,
                    ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);

        }
    }

    private ImsRegistrationListenerProxy createRegistrationListenerProxy(int serviceClass,
            ImsConnectionStateListener listener) {
        ImsRegistrationListenerProxy proxy =
                new ImsRegistrationListenerProxy(serviceClass, listener);
        return proxy;
    }

    private static void log(String s) {
        Rlog.d(TAG, s);
    }

    private static void loge(String s) {
        Rlog.e(TAG, s);
    }

    private static void loge(String s, Throwable t) {
        Rlog.e(TAG, s, t);
    }

    /**
     * Used for turning on IMS.if its off already
     */
    private void turnOnIms() throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsServiceProxy.turnOnIms();
        } catch (RemoteException e) {
            throw new ImsException("turnOnIms() ", e, ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    private boolean isImsTurnOffAllowed() {
        return isTurnOffImsAllowedByPlatformForSlot()
                && (!isWfcEnabledByPlatformForSlot()
                || !isWfcEnabledByUserForSlot());
    }

    private void setLteFeatureValues(boolean turnOn) {
        log("setLteFeatureValues: " + turnOn);
        try {
            ImsConfig config = getConfigInterface();
            if (config != null) {
                config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VOICE_OVER_LTE,
                        TelephonyManager.NETWORK_TYPE_LTE, turnOn ? 1 : 0, mImsConfigListener);

                if (isVolteEnabledByPlatformForSlot()) {
                    boolean ignoreDataEnabledChanged = getBooleanCarrierConfigForSlot(
                            CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS);
                    boolean enableViLte = turnOn && isVtEnabledByUserForSlot() &&
                            (ignoreDataEnabledChanged || isDataEnabled());
                    config.setFeatureValue(ImsConfig.FeatureConstants.FEATURE_TYPE_VIDEO_OVER_LTE,
                            TelephonyManager.NETWORK_TYPE_LTE,
                            enableViLte ? 1 : 0,
                            mImsConfigListener);
                }
            }
        } catch (ImsException e) {
            loge("setLteFeatureValues: exception ", e);
        }
    }

    private void setAdvanced4GMode(boolean turnOn) throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();
        log("setAdvanced4GMode :: turnOn=" + turnOn);
        // if turnOn: first set feature values then call turnOnIms()
        // if turnOff: only set feature values if IMS turn off is not allowed. If turn off is
        // allowed, first call turnOffIms() then set feature values
        if (turnOn) {
            setLteFeatureValues(turnOn);
            log("setAdvanced4GMode: turnOnIms");
            turnOnIms();
        } else {
            if (isImsTurnOffAllowed()) {
                log("setAdvanced4GMode: turnOffIms");
                turnOffIms();
            }
            setLteFeatureValues(turnOn);
        }
    }

    /**
     * Used for turning off IMS completely in order to make the device CSFB'ed.
     * Once turned off, all calls will be over CS.
     */
    private void turnOffIms() throws ImsException {
        checkAndThrowExceptionIfServiceUnavailable();

        try {
            mImsServiceProxy.turnOffIms();
        } catch (RemoteException e) {
            throw new ImsException("turnOffIms() ", e, ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
    }

    private void addToRecentDisconnectReasons(ImsReasonInfo reason) {
        if (reason == null) return;
        while (mRecentDisconnectReasons.size() >= MAX_RECENT_DISCONNECT_REASONS) {
            mRecentDisconnectReasons.removeFirst();
        }
        mRecentDisconnectReasons.addLast(reason);
    }

    /**
     * Death recipient class for monitoring IMS service.
     */
    private class ImsServiceDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mImsServiceProxy = null;
            mUt = null;
            mConfig = null;
            mEcbm = null;
            mMultiEndpoint = null;
        }
    }

    /**
     * Adapter class for {@link IImsRegistrationListener}.
     */
    private class ImsRegistrationListenerProxy extends IImsRegistrationListener.Stub {
        private int mServiceClass;
        private ImsConnectionStateListener mListener;

        public ImsRegistrationListenerProxy(int serviceClass,
                ImsConnectionStateListener listener) {
            mServiceClass = serviceClass;
            mListener = listener;
        }

        public boolean isSameProxy(int serviceClass) {
            return (mServiceClass == serviceClass);
        }

        @Deprecated
        public void registrationConnected() {
            if (DBG) {
                log("registrationConnected ::");
            }

            if (mListener != null) {
                mListener.onImsConnected(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
            }
        }

        @Deprecated
        public void registrationProgressing() {
            if (DBG) {
                log("registrationProgressing ::");
            }

            if (mListener != null) {
                mListener.onImsProgressing(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
            }
        }

        @Override
        public void registrationConnectedWithRadioTech(int imsRadioTech) {
            // Note: imsRadioTech value maps to RIL_RADIO_TECHNOLOGY
            //       values in ServiceState.java.
            if (DBG) {
                log("registrationConnectedWithRadioTech :: imsRadioTech=" + imsRadioTech);
            }

            if (mListener != null) {
                mListener.onImsConnected(imsRadioTech);
            }
        }

        @Override
        public void registrationProgressingWithRadioTech(int imsRadioTech) {
            // Note: imsRadioTech value maps to RIL_RADIO_TECHNOLOGY
            //       values in ServiceState.java.
            if (DBG) {
                log("registrationProgressingWithRadioTech :: imsRadioTech=" + imsRadioTech);
            }

            if (mListener != null) {
                mListener.onImsProgressing(imsRadioTech);
            }
        }

        @Override
        public void registrationDisconnected(ImsReasonInfo imsReasonInfo) {
            if (DBG) {
                log("registrationDisconnected :: imsReasonInfo" + imsReasonInfo);
            }

            addToRecentDisconnectReasons(imsReasonInfo);

            if (mListener != null) {
                mListener.onImsDisconnected(imsReasonInfo);
            }
        }

        @Override
        public void registrationResumed() {
            if (DBG) {
                log("registrationResumed ::");
            }

            if (mListener != null) {
                mListener.onImsResumed();
            }
        }

        @Override
        public void registrationSuspended() {
            if (DBG) {
                log("registrationSuspended ::");
            }

            if (mListener != null) {
                mListener.onImsSuspended();
            }
        }

        @Override
        public void registrationServiceCapabilityChanged(int serviceClass, int event) {
            log("registrationServiceCapabilityChanged :: serviceClass=" +
                    serviceClass + ", event=" + event);

            if (mListener != null) {
                mListener.onImsConnected(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
            }
        }

        @Override
        public void registrationFeatureCapabilityChanged(int serviceClass,
                int[] enabledFeatures, int[] disabledFeatures) {
            log("registrationFeatureCapabilityChanged :: serviceClass=" +
                    serviceClass);
            if (mListener != null) {
                mListener.onFeatureCapabilityChanged(serviceClass,
                        enabledFeatures, disabledFeatures);
            }
        }

        @Override
        public void voiceMessageCountUpdate(int count) {
            log("voiceMessageCountUpdate :: count=" + count);

            if (mListener != null) {
                mListener.onVoiceMessageCountChanged(count);
            }
        }

        @Override
        public void registrationAssociatedUriChanged(Uri[] uris) {
            if (DBG) log("registrationAssociatedUriChanged ::");

            if (mListener != null) {
                mListener.registrationAssociatedUriChanged(uris);
            }
        }

        @Override
        public void registrationChangeFailed(int targetAccessTech, ImsReasonInfo imsReasonInfo) {
            if (DBG) log("registrationChangeFailed :: targetAccessTech=" + targetAccessTech +
                    ", imsReasonInfo=" + imsReasonInfo);

            if (mListener != null) {
                mListener.onRegistrationChangeFailed(targetAccessTech, imsReasonInfo);
            }
        }
    }

    /**
     * Gets the ECBM interface to request ECBM exit.
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @return the ECBM interface instance
     * @throws ImsException if getting the ECBM interface results in an error
     */
    public ImsEcbm getEcbmInterface(int serviceId) throws ImsException {
        if (mEcbm == null || !mImsServiceProxy.isBinderAlive()) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsEcbm iEcbm = mImsServiceProxy.getEcbmInterface();

                if (iEcbm == null) {
                    throw new ImsException("getEcbmInterface()",
                            ImsReasonInfo.CODE_ECBM_NOT_SUPPORTED);
                }
                mEcbm = new ImsEcbm(iEcbm);
            } catch (RemoteException e) {
                throw new ImsException("getEcbmInterface()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
        return mEcbm;
    }

    /**
     * Gets the Multi-Endpoint interface to subscribe to multi-enpoint notifications..
     *
     * @param serviceId a service id which is obtained from {@link ImsManager#open}
     * @return the multi-endpoint interface instance
     * @throws ImsException if getting the multi-endpoint interface results in an error
     */
    public ImsMultiEndpoint getMultiEndpointInterface(int serviceId) throws ImsException {
        if (mMultiEndpoint == null || !mImsServiceProxy.isBinderAlive()) {
            checkAndThrowExceptionIfServiceUnavailable();

            try {
                IImsMultiEndpoint iImsMultiEndpoint = mImsServiceProxy.getMultiEndpointInterface();

                if (iImsMultiEndpoint == null) {
                    throw new ImsException("getMultiEndpointInterface()",
                            ImsReasonInfo.CODE_MULTIENDPOINT_NOT_SUPPORTED);
                }
                mMultiEndpoint = new ImsMultiEndpoint(iImsMultiEndpoint);
            } catch (RemoteException e) {
                throw new ImsException("getMultiEndpointInterface()", e,
                        ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
            }
        }
        return mMultiEndpoint;
    }

    /**
     * Resets ImsManager settings back to factory defaults.
     *
     * @deprecated Doesn't support MSIM devices. Use {@link #factoryResetSlot()} instead.
     *
     * @hide
     */
    public static void factoryReset(Context context) {
        // Set VoLTE to default
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED,
                ImsConfig.FeatureValueConstants.ON);

        // Set VoWiFi to default
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED,
                getBooleanCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);

        // Set VoWiFi mode to default
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_MODE,
                getIntCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));

        // Set VoWiFi roaming to default
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ROAMING_ENABLED,
                getBooleanCarrierConfig(context,
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);

        // Set VT to default
        android.provider.Settings.Global.putInt(context.getContentResolver(),
                android.provider.Settings.Global.VT_IMS_ENABLED,
                ImsConfig.FeatureValueConstants.ON);

        // Push settings to ImsConfig
        ImsManager.updateImsServiceConfig(context,
                SubscriptionManager.getDefaultVoicePhoneId(), true);
    }

    /**
     * Resets ImsManager settings back to factory defaults.
     *
     * @hide
     */
    public void factoryResetSlot() {
        int subId = getSubId(mPhoneId);
        // Set VoLTE to default
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.ENHANCED_4G_MODE_ENABLED + subId,
                ImsConfig.FeatureValueConstants.ON);

        // Set VoWiFi to default
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ENABLED + subId,
                getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);

        // Set VoWiFi mode to default
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_MODE + subId,
                getIntCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT));

        // Set VoWiFi roaming to default
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.WFC_IMS_ROAMING_ENABLED + subId,
                getBooleanCarrierConfigForSlot(
                        CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL) ?
                        ImsConfig.FeatureValueConstants.ON : ImsConfig.FeatureValueConstants.OFF);

        // Set VT to default
        android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                android.provider.Settings.Global.VT_IMS_ENABLED + subId,
                ImsConfig.FeatureValueConstants.ON);

        // Push settings to ImsConfig
        updateImsServiceConfigForSlot(true);
    }

    private boolean isDataEnabled() {
        int subId = getSubId(mPhoneId);
        return SystemProperties.getBoolean(DATA_ENABLED_PROP + subId, true);
    }

    /**
     * Set data enabled/disabled flag.
     * @param enabled True if data is enabled, otherwise disabled.
     */
    public void setDataEnabled(boolean enabled) {
        int subId = getSubId(mPhoneId);
        log("setDataEnabled: " + enabled);
        SystemProperties.set(DATA_ENABLED_PROP + subId, enabled ? TRUE : FALSE);
    }

    private boolean isVolteProvisioned() {
        int subId = getSubId(mPhoneId);
        return SystemProperties.getBoolean(VOLTE_PROVISIONED_PROP + subId, true);
    }

    private void setVolteProvisionedProperty(boolean provisioned) {
        int subId = getSubId(mPhoneId);
        SystemProperties.set(VOLTE_PROVISIONED_PROP + subId, provisioned ? TRUE : FALSE);
    }

    private boolean isWfcProvisioned() {
        int subId = getSubId(mPhoneId);
        return SystemProperties.getBoolean(WFC_PROVISIONED_PROP + subId, true);
    }

    private void setWfcProvisionedProperty(boolean provisioned) {
        int subId = getSubId(mPhoneId);
        SystemProperties.set(WFC_PROVISIONED_PROP + subId, provisioned ? TRUE : FALSE);
    }

    private boolean isVtProvisioned() {
        int subId = getSubId(mPhoneId);
        return SystemProperties.getBoolean(VT_PROVISIONED_PROP + subId, true);
    }

    private void setVtProvisionedProperty(boolean provisioned) {
        int subId = getSubId(mPhoneId);
        SystemProperties.set(VT_PROVISIONED_PROP + subId, provisioned ? TRUE : FALSE);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final TelephonyManager telephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        final int subId = getSubId(mPhoneId);
        pw.println("ImsManager:");
        pw.println("  mPhoneId = " + mPhoneId);
        pw.println("  mConfigUpdated = " + mConfigUpdated);
        pw.println("  mImsServiceProxy = " + mImsServiceProxy);
        pw.println("  mDataEnabled = " + isDataEnabled());
        pw.println("  ignoreDataEnabledChanged = " + getBooleanCarrierConfigForSlot(
                CarrierConfigManager.KEY_IGNORE_DATA_ENABLED_CHANGED_FOR_VIDEO_CALLS));

        pw.println("  isGbaValid = " + isGbaValidForSlot());
        pw.println("  isImsTurnOffAllowed = " + isImsTurnOffAllowed());
        pw.println("  isNonTtyOrTtyOnVolteEnabled = " + isNonTtyOrTtyOnVolteEnabledForSlot());

        pw.println("  isVolteEnabledByPlatform = " + isVolteEnabledByPlatformForSlot());
        pw.println("  isVolteProvisionedOnDevice = " + isVolteProvisionedOnDeviceForSlot());
        pw.println("  isEnhanced4gLteModeSettingEnabledByUser = " +
                isEnhanced4gLteModeSettingEnabledByUserForSlot());
        pw.println("  isVtEnabledByPlatform = " + isVtEnabledByPlatformForSlot());
        pw.println("  isVtEnabledByUser = " + isVtEnabledByUserForSlot());

        pw.println("  isWfcEnabledByPlatform = " + isWfcEnabledByPlatformForSlot());
        pw.println("  isWfcEnabledByUser = " + isWfcEnabledByUserForSlot());
        pw.println("  getWfcMode = " + getWfcModeForSlot(telephonyManager.isNetworkRoaming(subId)));
        pw.println("  isWfcRoamingEnabledByUser = " + isWfcRoamingEnabledByUserForSlot());

        pw.println("  isVtProvisionedOnDevice = " + isVtProvisionedOnDeviceForSlot());
        pw.println("  isWfcProvisionedOnDevice = " + isWfcProvisionedOnDeviceForSlot());
        pw.flush();
    }

    private int getSubId(int phoneId) {
        final int[] subIds = SubscriptionManager.getSubId(phoneId);
        int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (subIds != null && subIds.length >= 1) {
            subId = subIds[0];
        }

        return subId;
    }
}
