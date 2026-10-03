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
  hookShaderSource();
});

// Pi 4 / V3D speaks GLSL ES 3.10. Singularity's Hypatie viewer asks for 3.20
// and the failed compile kills the GL thread, which sends the app home.
function downgradeGlsl(source) {
  if (source == null) return source;
  var text = '' + source;
  if (text.indexOf('320 es') < 0) return source;
  text = text.replace(/#version[ \t]+320[ \t]+es/g, '#version 310 es');
  text = text.replace(/precision[ \t]+highp[ \t]+image2D[ \t]*;/g, '');
  text = text.replace(/precision[ \t]+highp[ \t]+uimage2D[ \t]*;/g, '');
  text = text.replace(/(\d+\.\d+)[fF]/g, '$1');
  return text;
}

function hookShaderSource() {
  var GLES20 = Java.use('android.opengl.GLES20');
  var method = GLES20.glShaderSource.overload('int', 'java.lang.String');
  method.implementation = function (shader, source) {
    method.call(this, shader, downgradeGlsl(source));
  };
  try {
    var f = new File('/data/local/tmp/glsl-310.ok', 'w');
    f.write('hooked');
    f.flush();
    f.close();
  } catch (err) {}
}
