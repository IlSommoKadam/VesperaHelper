'use strict';

function hookAvailability(name) {
  var Cls = Java.use(name);
  var overloads = Cls.isGooglePlayServicesAvailable.overloads;
  for (var i = 0; i < overloads.length; i++) {
    overloads[i].implementation = function () { return 0; };
  }
}

Java.perform(function () {
  hookAvailability('com.google.android.gms.common.GoogleApiAvailability');
  hookAvailability('com.google.android.gms.common.GoogleApiAvailabilityLight');
  hookAvailability('com.google.android.gms.common.GooglePlayServicesUtilLight');
  hookAvailability('com.google.android.gms.common.GooglePlayServicesUtil');
  try {
    var Verifier = Java.use('com.google.android.gms.common.GoogleSignatureVerifier');
    var overloads = Verifier.isGooglePublicSignedPackage.overloads;
    for (var i = 0; i < overloads.length; i++) {
      overloads[i].implementation = function () { return true; };
    }
  } catch (err) {}
  try {
    var f = new File('/data/local/tmp/gms-spoof.ok', 'w');
    f.write('ok');
    f.flush();
    f.close();
  } catch (err) {}
});
