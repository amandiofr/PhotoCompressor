# Add project specific ProGuard rules here.
-keepattributes SourceFile,LineNumberTable

# Firebase Crashlytics référence une classe Android récente (profilage sur
# API très haute) absente du android.jar de compilation ; le chemin de code
# correspondant n'est jamais atteint sur les API supportées par l'app.
-dontwarn android.os.ProfilingTrigger$Builder
-dontwarn android.os.ProfilingTrigger
