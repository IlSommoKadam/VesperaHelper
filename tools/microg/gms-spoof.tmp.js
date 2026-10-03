/*
 * Make Singularity accept microG LocationServices on vanilla AOSP.
 * The Play Services client otherwise throws SERVICE_INVALID (wrong signature)
 * and crashes in LocationRepository.getLastLocationV2.
 */
'use strict';

console.log('[gms-spoof] script evaluated');

function hookAvailability(name) {
  try {
    const Cls = Java.use(name);
    Cls.isGooglePlayServicesAvailable.overloads.forEach(function (overload) {
      overload.implementation = function () {
        return 0;
      };
    });
    console.log('[gms-spoof] hooked ' + name);
  } catch (err) {
    console.log('[gms-spoof] skip ' + name + ': ' + err);
  }
}

function hookSignatureVerifier() {
  const names = [
    'com.google.android.gms.common.GoogleSignatureVerifier',
    'com.google.android.gms.common.internal.GoogleApiAvailabilityCache'
  ];
  names.forEach(function (name) {
    try {
      const Cls = Java.use(name);
      Cls.class.getDeclaredMethods().forEach(function (method) {
        const n = method.getName();
        if (n.indexOf('isGoogle') === -1 && n.indexOf('isPlay') === -1 && n !== 'get') {
          return;
        }
      });
    } catch (err) {
      console.log('[gms-spoof] verifier skip ' + name + ': ' + err);
    }
  });
  try {
    const Verifier = Java.use('com.google.android.gms.common.GoogleSignatureVerifier');
    Verifier.isGooglePublicSignedPackage.overloads.forEach(function (overload) {
      overload.implementation = function () { return true; };
    });
    console.log('[gms-spoof] hooked GoogleSignatureVerifier.isGooglePublicSignedPackage');
  } catch (err) {
    console.log('[gms-spoof] no isGooglePublicSignedPackage: ' + err);
  }
}

function hookLastLocationFallback() {
  try {
    const Client = Java.use('com.google.android.gms.location.FusedLocationProviderClient');
    Client.getLastLocation.overloads.forEach(function (overload) {
      overload.implementation = function () {
        const task = overload.apply(this, arguments);
        return task;
      };
    });
    console.log('[gms-spoof] fused location passthrough');
  } catch (err) {
    console.log('[gms-spoof] fused skip: ' + err);
  }
}

function start() {
  if (typeof Java === 'undefined' || !Java.available) {
    setTimeout(start, 50);
    return;
  }
  Java.perform(function () {
    hookAvailability('com.google.android.gms.common.GoogleApiAvailability');
    hookAvailability('com.google.android.gms.common.GoogleApiAvailabilityLight');
    hookAvailability('com.google.android.gms.common.GooglePlayServicesUtilLight');
    hookAvailability('com.google.android.gms.common.GooglePlayServicesUtil');
    hookSignatureVerifier();
    hookLastLocationFallback();
    console.log('[gms-spoof] ready');
  });
}

start();
