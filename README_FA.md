# TIREXNET Profile Editor + DNS Scanner — خروجی تغییرات

این بسته برای آخرین ساختار مخزن `HRMPMADEUA/HRMP-AETHER` تهیه شده و فایل‌های موجود را مستقیماً جایگزین نمی‌کند.

## روش ساخت ZIP نهایی از آخرین مخزن

1. مخزن فعلی را در Termux به‌روز کنید:
   `cd ~/HRMP-AETHER && git pull`
2. این بسته را از حالت فشرده خارج کنید.
3. اسکریپت را روی ریشه مخزن اجرا کنید:
   `python apply_tirexnet_profile_dns_upgrade.py ~/HRMP-AETHER`
4. اسکریپت پوشه `TIREXNET-PROFILE-EDITOR-DNS-SCANNER-READY` و ZIP همان نام را می‌سازد. فقط فایل‌های جدید و اصلاح‌شده داخل خروجی هستند.
5. فایل‌های داخل ZIP نهایی را با همان مسیرها به مخزن GitHub اضافه/جایگزین کنید.

اسکریپت سورس اصلی را تغییر نمی‌دهد؛ فقط فایل‌های خروجی را در پوشه READY می‌سازد. اگر ساختار مخزن با نسخه بررسی‌شده فرق کرده باشد، قبل از تولید خروجی متوقف می‌شود.

## قابلیت‌های پیاده‌سازی‌شده

- شناسه و تنظیمات مستقل هر پروفایل با نسخه‌بندی Schema و مهاجرت هنگام تغییر raw profile.
- DNS Scanner روی شبکه فعال Android: UDP، TCP، DoT با long-press، DoH برای endpointهای HTTPS DNS، latency، packet loss، پاسخ معتبر، EDNS و تشخیص احتمالی NXDOMAIN hijack.
- برچسب DNS Reachable از Tunnel Tested جداست؛ Scanner هیچ‌وقت موفقیت تونل را ادعا نمی‌کند.
- انتخاب Resolver به Xray، WireGuard/AmneziaWG، StormDNS، MasterDNS، CottenDNS و مدل Resolver واقعی SlipNet منتقل می‌شود.
- ویرایش فیلدهای Decodeشده برای فرمت‌هایی که round-trip امن دارند؛ در حضور فیلد ناشناخته، ویرایش فیلدی غیرفعال و raw حفظ می‌شود.
- Duplicate پروفایل برای فرمت‌های پشتیبانی‌شده.

## محدودیت‌های واقعی

- DoH فقط برای endpoint HTTPS صریحی تست می‌شود که کاربر وارد می‌کند؛ نتیجه DoH به IP Resolver دیگری نسبت داده نمی‌شود.
- DoT با long-press روی نتیجه و TLS استاندارد پلتفرم تست می‌شود.
- نتیجه DNS Scanner معادل تست handshake یا Tunnel Tested نیست.
- برای MHRV و برخی فرمت‌های vendor-specific، در صورت وجود فیلدهای ناشناخته، Editor عمداً raw-only می‌ماند تا اطلاعات از بین نرود.
- Build اندروید در محیط تولید این بسته انجام نشده است؛ Android SDK/NDK و وابستگی‌های Native پروژه در این محیط حاضر نبودند. این خروجی را Build موفق تلقی نکنید.
