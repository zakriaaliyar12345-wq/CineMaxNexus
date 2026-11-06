package com.cinemax.nexus;

/** Test setup only. Runtime grants are never bypassed in the production app. */
public final class MediaTestPermissions {
    private MediaTestPermissions() { }
    public static void capture(String name) {
        android.app.Instrumentation instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        if (bitmap == null) return;
        java.io.File file = new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),name);
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
    public static void grant() {
        android.app.Instrumentation instrumentation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        for(String command : new String[]{"input keyevent 224", "wm dismiss-keyguard",
                "settings put secure immersive_mode_confirmations confirmed"}) {
            try(android.os.ParcelFileDescriptor output=instrumentation.getUiAutomation().executeShellCommand(command);
                java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)) {
                byte[] drain=new byte[256]; while(input.read(drain)!=-1) { /* Wait for completion. */ }
            } catch(java.io.IOException e) { throw new AssertionError(e); }
        }
        String permission = android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_VIDEO" : "android.permission.READ_EXTERNAL_STORAGE";
        try (android.os.ParcelFileDescriptor ignored = instrumentation.getUiAutomation()
                .executeShellCommand("pm grant " + instrumentation.getTargetContext().getPackageName()
                        + " " + permission)) {
            android.os.SystemClock.sleep(500);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try (android.os.ParcelFileDescriptor ignored = instrumentation.getUiAutomation()
                    .executeShellCommand("pm grant " + instrumentation.getTargetContext().getPackageName()
                            + " android.permission.POST_NOTIFICATIONS")) {
                android.os.SystemClock.sleep(500);
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }
    }
}
