# خريطة المملكة (Android native)

## 1) بناء التطبيق (APK)
ارفع هذا المجلد إلى مستودع GitHub (الجذر) ← Actions ← "Build APK" ← Run workflow ← حمّل `saudi-map-apk`.

## 2) بيانات الاتجاهات والبحث دون إنترنت
Actions ← "Build offline data" ← Run workflow (يستغرق عدة دقائق إلى نحو ساعة).
ينشئ إصداراً (Release) باسم `data` فيه `ksa.graph` و `places.db`.
في MainActivity.kt غيّر `dataBase` إلى اسم مستودعك إن اختلف، ثم أعد بناء الـAPK.
داخل التطبيق: دون إنترنت ← "تنزيل بيانات البحث والاتجاهات".
