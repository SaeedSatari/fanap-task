# Netmon — مدیریت تجهیزات شبکه و پایش اتصال (Ping)

پیاده‌سازی تمرین Senior Java Developer با **Java 17 + Spring Boot 3.3 + Spring Data JPA + H2 (file)**.

## اجرا

### با Docker Compose (پیشنهادی)

نیازی به نصب Java یا Maven نیست.

```bash
docker compose up -d --build                      # ساخت و اجرا؛ پایگاه‌داده روی volume می‌ماند
docker compose --profile test run --rm smoke      # اسموک‌تست (بعد از healthy شدن برنامه شروع می‌شود)
docker compose restart netmon                     # نمایش recovery بعد از restart
docker compose logs -f netmon                     # لاگ برنامه
docker compose down                               # توقف (با -v داده‌ها هم پاک می‌شود)
```

- سرویس `smoke` از همان ایمیج برنامه استفاده می‌کند (`bash` و `curl` دارد)، پس چیزی دانلود نمی‌کند.
- ساخت اول به‌دلیل دانلود وابستگی‌های Maven طولانی است؛ Maven با cache mount کار می‌کند و دفعات بعد سریع است.
- اگر پورت ۸۰۸۰ مشغول است، در `docker-compose.yml` مقدار `"8080:8080"` را عوض کنید (مثلاً `"9090:8080"`).

### بدون Docker

```bash
mvn test              # همه‌ی تست‌های JUnit
mvn spring-boot:run   # اجرا روی http://localhost:8080
```

### تست API

- `./smoke-test.sh [base-url]` — اسموک‌تست خودکار (فقط bash + curl، حدود ۱۵ ثانیه). اگر سرور در دسترس نباشد با پیام روشن و کد خروج ۲ متوقف می‌شود؛ در پایان تعداد موفق/ناموفق چاپ می‌شود.
- `requests.http` — نمونه درخواست‌ها (IntelliJ / VS Code REST Client).

داده‌ها در `./data/netmon.mv.db` (یا volume داکر) ذخیره می‌شود تا بعد از restart باقی بماند؛ برای نمایش recovery لازم است.

## معماری

انتخاب: **Modular Monolith** با دو «سرویس» منطقی که فقط از طریق HTTP و `RestTemplate` با هم صحبت می‌کنند.

```
Client ─► /api/v1/**  (PingApiController, MonitorApiController, DeviceController)
              │  RestTemplate (timeout + error mapping)
              ├─► /internal/ping/**     Ping service    (ثبت درخواست، اجرای async، تاریخچه)
              └─► /internal/monitor/**  Monitor service (آمار دسترس‌پذیری و latency)
                         │
                   Domain: Device, PingOperation  ─►  H2/JPA
                         │
                   Worker pool ─► PingProbe (Mock)
```

**چرا Monolith؟** برای این دامنه کوچک، دیپلوی و تراکنش ساده‌تر است. ولی مرز سرویس‌ها واقعی است
(قرارداد HTTP، کلاینت جدا، timeout جدا). برای جدا کردن هرکدام کافی است
`netmon.clients.ping.base-url` / `netmon.clients.monitor.base-url` را روی آدرس دیگری بگذارید؛
اولین کاندیدای جداسازی Ping worker است (نیاز به scale جدا دارد). در آن حالت باید `/internal/**` فقط در شبکه داخلی در دسترس باشد
و دیتابیس مشترک با یک message queue/outbox جایگزین شود.

## مدل داده

- `devices`: id، name، ipAddress (یکتا)، type (`MODEM|RADIO`)، monitoringEnabled، lastStatus (`UP|DOWN|UNKNOWN`)، lastCheckedAt
- `ping_operations`: id (UUID)، deviceId، idempotencyKey، status، attempts/maxAttempts، nextAttemptAt، leaseExpiresAt،
  reachable، responseTimeMs، errorMessage، createdAt/startedAt/finishedAt  
  قید یکتایی `(device_id, idempotency_key)`.

## چرخه‌ی وضعیت عملیات

```
PENDING ──claim──► RUNNING ──موفق──────────────────────────► SUCCEEDED (reachable true/false)
   ▲                  │ خطا/timeout و attempt باقی است
   └──── backoff ◄────┤
                      ├─ خطا و attempt تمام ───────────────► FAILED
                      ├─ timeout و attempt تمام ───────────► TIMED_OUT
                      └─ lease منقضی / restart ───────────► PENDING (attempt باقی) یا FAILED
```

نتیجه‌ی «دستگاه خاموش است» یک **عملیات موفق** با `reachable=false` است؛ `FAILED/TIMED_OUT` یعنی خودِ probe نتیجه‌ی قطعی نداد
(وضعیت دستگاه در این حالت `UNKNOWN` می‌شود و در محاسبه‌ی availability لحاظ نمی‌شود).

## صحت و هم‌زمانی

| مسئله | راهکار |
|---|---|
| درخواست تکراری | هدر `Idempotency-Key`: همان عملیات قبلی برگردانده می‌شود (200 + `Idempotent-Replayed: true`)، ping دوباره اجرا نمی‌شود. قید unique دیتابیس هم لایه‌ی دفاعی است. پایش خودکار هم کلید بر پایه‌ی بازه‌ی زمانی دارد. |
| اجرای هم‌زمان روی یک تجهیز | هنگام ثبت، ردیف device با `SELECT … FOR UPDATE` قفل می‌شود؛ اگر عملیات `PENDING/RUNNING` وجود داشته باشد پاسخ `409 ACTIVE_OPERATION_EXISTS`. حذف device هم در همین حالت 409 است. |
| دو worker روی یک عملیات | `claim` یک UPDATE شرطی (`PENDING→RUNNING`) است؛ فقط یک نفر برنده می‌شود. |
| worker «زامبی» | هر تغییر وضعیت با شماره‌ی attempt محافظت می‌شود (fencing)؛ نتیجه‌ی attempt قدیمی نادیده گرفته می‌شود. |
| نتیجه‌ی قدیمی روی وضعیت جدید | `lastStatus` فقط وقتی به‌روز می‌شود که `lastCheckedAt` جدیدتر یا مساوی باشد. |
| Timeout | probe در thread جدا اجرا می‌شود و با `Future.get(timeout)` + `cancel(true)` قطع می‌شود. |
| Retry | حداکثر `max-attempts` بار، backoff نمایی (1s, 2s, 4s … با سقف). |
| Restart / crash | هر `RUNNING` یک lease دارد. هنگام startup همه‌ی `RUNNING`های باقی‌مانده و در حین اجرا هر lease منقضی، دوباره `PENDING` یا (اگر attempt تمام شده) `FAILED` می‌شود. Ping idempotent است، پس اجرای مجدد امن است. Sweeper هر ثانیه `PENDING`های سررسیده را هم dispatch می‌کند، پس هیچ عملیاتی در وضعیت نامشخص نمی‌ماند. |
| Queue پر | عملیات در DB `PENDING` می‌ماند و بعداً توسط sweeper برداشته می‌شود. |

> فرض: هنگام startup همه‌ی `RUNNING`ها متعلق به process قبلی‌اند (تک‌instance). برای چند instance،
> `netmon.recovery.requeue-running-on-startup=false` بگذارید تا فقط lease ملاک باشد.

## رفتار RestTemplate (Timeout، خطای HTTP، پاسخ نامعتبر)

همه در `DownstreamCaller` تعریف شده است:

| وضعیت | نتیجه برای کلاینت API | Retry |
|---|---|---|
| Timeout (connect/read) | `504 DOWNSTREAM_TIMEOUT` | فقط GET (پیش‌فرض ۱ بار) |
| اتصال ممکن نیست | `503 DOWNSTREAM_UNAVAILABLE` | فقط GET |
| HTTP 5xx | `502 DOWNSTREAM_ERROR` | فقط GET |
| HTTP 4xx | همان status و code سرویس مقصد (مثلاً 404 `DEVICE_NOT_FOUND`، 409) | خیر |
| بدنه‌ی خالی، JSON خراب، content-type اشتباه، فیلدهای ضروری ناموجود | `502 INVALID_DOWNSTREAM_RESPONSE` | خیر |

POST هرگز خودکار retry نمی‌شود؛ کلاینت می‌تواند با همان `Idempotency-Key` با خیال راحت دوباره بفرستد.
همه‌ی خطاها با یک قالب یکسان برمی‌گردند: `{timestamp, status, code, message, details?}`.

## API

| Method | Path | توضیح |
|---|---|---|
| POST | `/api/v1/devices` | ایجاد (201) |
| GET | `/api/v1/devices?page&size` | لیست صفحه‌بندی‌شده |
| GET/PUT/DELETE | `/api/v1/devices/{id}` | خواندن / ویرایش / حذف |
| PUT | `/api/v1/devices/{id}/monitoring` | `{ "enabled": true/false }` |
| POST | `/api/v1/devices/{id}/pings` | شروع ping غیرهم‌زمان: 202 (جدید) / 200 (تکراری) |
| GET | `/api/v1/pings/{operationId}` | وضعیت و نتیجه‌ی عملیات |
| GET | `/api/v1/devices/{id}/pings?limit` | تاریخچه |
| GET | `/api/v1/devices/{id}/monitor?window` | آمار: availability، میانگین/min/max زمان پاسخ، آخرین عملیات‌ها |

اگر `monitoringEnabled=false` باشد، ping دستی هم رد می‌شود (`422 MONITORING_DISABLED`) و پایش خودکار انجام نمی‌شود.
برای دستگاه‌هایی که پایش‌شان فعال است، هر `netmon.monitoring.interval-ms` (پیش‌فرض ۳۰ ثانیه) خودکار ping ثبت می‌شود.

## Ping شبیه‌سازی‌شده

`MockPingProbe` برای IPهای مستند (RFC 5737) رفتار قطعی دارد تا هر مسیر خطا قابل نمایش باشد:

| IP | رفتار |
|---|---|
| `192.0.2.x` | دستگاه خاموش (`SUCCEEDED`, `reachable=false`) |
| `198.51.100.x` | probe جواب نمی‌دهد → timeout → `TIMED_OUT` |
| `203.0.113.x` | probe خطا می‌دهد → retry → `FAILED` |
| بقیه | latency تصادفی؛ احتمال خاموشی و خطای گذرا قابل تنظیم (`netmon.mock.*`) |

## تست‌ها

- `DeviceRequestValidationTest`: اعتبارسنجی IP/name/type
- `DeviceServiceTest`: IP تکراری، حذف هنگام عملیات فعال، به‌روزرسانی
- `PingWorkerTest`: موفق / خطا / retry با backoff / timeout / آخرین attempt / claim ناموفق
- `MonitorServiceTest`: محاسبه‌ی آمار
- `PingServiceClientTest` و `MonitorServiceClientTest` (`MockRestServiceServer`): timeout، 5xx، 4xx، connection refused، JSON خراب، بدنه‌ی خالی، فیلد ناموجود، retry فقط برای GET
- `PingOperationStoreIntegrationTest`: انتقال وضعیت‌ها، ذخیره‌ی نتیجه، claim هم‌زمان، attempt قدیمی، **recovery بعد از restart**
- `PingSubmissionConcurrencyTest`: ۸ درخواست هم‌زمان → فقط یک عملیات؛ کلید یکسان → یک عملیات؛ تکرار کلید بعد از اتمام
- `PingApiEndToEndTest`: کل جریان روی HTTP واقعی
- `smoke-test.sh`: اسموک‌تست روی برنامه‌ی در حال اجرا (CRUD، idempotency، 409 هم‌زمان، timeout، retry، ۸ درخواست موازی)

## محدودیت‌ها و گام‌های بعدی

- IPv6 پشتیبانی نمی‌شود (فقط IPv4 اعتبارسنجی می‌شود).
- `/internal/**` احراز هویت ندارد؛ در محیط واقعی باید فقط داخل شبکه/با mTLS در دسترس باشد.
- برای production: PostgreSQL + Flyway به‌جای `ddl-auto=update`، و Micrometer برای متریک صف/تأخیر.
