# PDF Studio Desktop — תוכנית עבודה מקצועית
## מערכת ניהול נתוני משתמשים בענן + אשף הכוונה + פריסת UI מחדש

---

## סקירת מצב קיים

| רכיב | מצב נוכחי |
|------|-----------|
| כרטיסיות | 6: Home, Sign, Tools, Viewer, Remote Sign, Settings |
| אחסון | מקומי בלבד — קבצי JSON ב-`~/PDF Studio/` |
| משתמשים | אין הרשמה, התחברות, או סנכרון ענן |
| אשף הכוונה | לא קיים — האפליקציה נפתחת ישירות למסך הבית |
| Layout | גלילה רציפה בכל כרטיסיה |

---

## שלב 1: פריסה מחדש של תוכן הכרטיסיות (UI Layout)

**עיקרון**: כל כרטיסיה מתאימה ל-viewport אחד (100vh) ללא גלילה חיצונית.

### 1.1 CSS Global — הגבלת גובה כל כרטיסיה
- `.content` — `height: 100vh; overflow: hidden`
- `.tab.active` — `display: flex; flex-direction: column; height: 100%; overflow: hidden`
- גלילה פנימית רק בקונטיינרים ספציפיים

### 1.2 Home — Grid compact
- כפתורי פעולה בשורה אופקית עליונה
- רשימת קבצים אחרונים בתוך container עם max-height וגלילה פנימית

### 1.3 Sign — Fit-to-viewport
- צמצום padding/margins
- PDF canvas עם max-height מחושב
- פאנל ימני עם overflow-y: auto

### 1.4 Tools — Grid צפוף 3-4 עמודות
- כרטיסי כלים קומפקטיים
- Container עם max-height וגלילה פנימית

### 1.5 Viewer — Canvas responsive
- Canvas עם max-height: calc(100vh - header)

### 1.6 Remote — עמודות עם max-height פנימי
- כל עמודה עם overflow-y: auto

### 1.7 Settings — התאמות קלות

---

## שלב 2: מערכת ניהול נתוני משתמשים בענן (Firebase)

**שירות נבחר**: Firebase (Google) — Spark Plan (חינם לשימוש מסחרי)
- **Firebase Auth**: הזדהות משתמשים (Email+Password, Google Sign-In)
- **Cloud Firestore**: מסד נתונים (1GB, 50K קריאות/יום חינם)

### מיפוי נתונים

| נתון | מקומי (כיום) | ענן (חדש) |
|------|-------------|-----------|
| חתימות שמורות | `saved_signatures.json` | `users/{uid}/signatures` |
| קבצים אחרונים | `recents.json` | `users/{uid}/recents` |
| הגדרות אפליקציה | `settings.json` | `users/{uid}/settings` |
| הגדרות SMTP | בזיכרון בלבד | `users/{uid}/smtp` |
| פרופיל משתמש | לא קיים | `users/{uid}/profile` |

### 2.1 התקנת Firebase SDK
```bash
npm install firebase
```

### 2.2 קובץ קונפיגורציה — `renderer/core/firebase.js`
- אתחול Firebase App
- ייצוא Firestore + Auth instances

### 2.3 מודול Auth — `renderer/core/auth.js`
- הרשמה/התחברות Email+Password
- Google Sign-In
- Logout
- מעקב אחר מצב התחברות (onAuthStateChanged)

### 2.4 מודול סנכרון ענן — `renderer/core/cloudSync.js`
- CRUD לכל סוג נתון (signatures, recents, settings, smtp, profile)
- סנכרון דו-כיווני: מקומי↔ענן (ענן מנצח)
- מצב Offline: fallback מקומי, סנכרון בחזרה לענן כשיש חיבור

### 2.5 מסך Login/Register
- Overlay לפני הגישה לאפליקציה
- טפסי כניסה/הרשמה
- כפתור Google Sign-In
- אפשרות "המשך כאורח" (מצב מקומי בלבד)

### 2.6 שילוב בתוך app.js
- החלפת `saveJson`/`loadJson` בקריאות cloudSync
- הוספת פרטי משתמש + Logout בסרגל הצד

### 2.7 עדכון CSP ב-index.html
- התרת חיבורים ל-Firebase domains

---

## שלב 3: אשף הכוונה בכניסה ראשונית (First-Time Setup Wizard)

### שלבי האשף

| # | כותרת | תוכן |
|---|-------|-------|
| 1 | ברוכים הבאים | הסבר על PDF Studio ויכולותיו |
| 2 | הגדרת פרופיל | שם מלא, חברה (אופציונלי) |
| 3 | חתימה ראשונה | ציור חתימה דיגיטלית שמורה כברירת מחדל |
| 4 | הגדרות SMTP | חיבור שרת מייל (אופציונלי + דלג) |
| 5 | מוכן! | סיכום + כפתור "התחל לעבוד" |

### 3.1 מודול Wizard — `renderer/features/wizard.js`
- מודאל full-screen עם step indicator
- לוגיקת מעבר בין שלבים
- Canvas ציור חתימה (שימוש חוזר בקוד קיים)
- שמירת נתונים לענן/מקומי

### 3.2 עיצוב CSS
- מודאל אלגנטי עם אנימציות
- תואם לעיצוב Warm Editorial Dark

### 3.3 שילוב באפליקציה
- בדיקת `wizardCompleted` flag
- הפעלת אשף רק למשתמש חדש
- שמירת כל נתוני האשף מיד עם סיום

---

## סדר ביצוע

```
שלב 1 (UI Layout)  →  שלב 2 (Firebase + ענן)  →  שלב 3 (אשף)
```

## קבצים חדשים

| קובץ | תיאור |
|------|-------|
| `renderer/core/firebase.js` | אתחול Firebase |
| `renderer/core/auth.js` | שירות הזדהות |
| `renderer/core/cloudSync.js` | סנכרון נתונים לענן |
| `renderer/features/wizard.js` | אשף הכוונה ראשוני |

## תלויות חדשות

| חבילה | שימוש |
|-------|-------|
| `firebase` | Auth + Firestore SDK |
