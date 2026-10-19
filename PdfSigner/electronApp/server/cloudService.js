/**
 * Firebase Cloud Service — PDF Studio Desktop
 * Runs in the Electron main process (Node.js).
 *
 * Uses Firebase Spark (free) plan:
 *   - Auth: email/password + Google
 *   - Firestore: user data sync
 *
 * Replace firebaseConfig below with your project credentials from:
 *   https://console.firebase.google.com  →  Project Settings  →  Web App
 */

const { initializeApp } = require('firebase/app');
const {
  getAuth,
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  GoogleAuthProvider,
  signInWithCredential,
} = require('firebase/auth');
const {
  getFirestore,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  collection,
  getDocs,
  deleteDoc,
  serverTimestamp,
} = require('firebase/firestore');

/* ── Firebase project config ──────────────────────────────────────── */
const firebaseConfig = {
  apiKey:            'REPLACE_WITH_YOUR_API_KEY',
  authDomain:        'REPLACE_WITH_YOUR_PROJECT.firebaseapp.com',
  projectId:         'REPLACE_WITH_YOUR_PROJECT_ID',
  storageBucket:     'REPLACE_WITH_YOUR_PROJECT.appspot.com',
  messagingSenderId: 'REPLACE_WITH_YOUR_SENDER_ID',
  appId:             'REPLACE_WITH_YOUR_APP_ID',
};

let app = null;
let auth = null;
let db = null;
let currentUser = null;

/* ── Initialization ───────────────────────────────────────────────── */

function init() {
  if (app) return;
  app = initializeApp(firebaseConfig);
  auth = getAuth(app);
  db = getFirestore(app);

  onAuthStateChanged(auth, (user) => {
    currentUser = user || null;
  });
}

function isConfigured() {
  return firebaseConfig.apiKey !== 'REPLACE_WITH_YOUR_API_KEY';
}

/* ── Auth ─────────────────────────────────────────────────────────── */

async function register(email, password) {
  init();
  const cred = await createUserWithEmailAndPassword(auth, email, password);
  currentUser = cred.user;
  // Create initial profile document
  await setDoc(doc(db, 'users', cred.user.uid, 'data', 'profile'), {
    email,
    displayName: '',
    company: '',
    wizardCompleted: false,
    createdAt: serverTimestamp(),
  });
  return { uid: cred.user.uid, email: cred.user.email };
}

async function login(email, password) {
  init();
  const cred = await signInWithEmailAndPassword(auth, email, password);
  currentUser = cred.user;
  return { uid: cred.user.uid, email: cred.user.email };
}

async function loginWithGoogleToken(idToken) {
  init();
  const credential = GoogleAuthProvider.credential(idToken);
  const cred = await signInWithCredential(auth, credential);
  currentUser = cred.user;
  // Create profile if new user
  const profileRef = doc(db, 'users', cred.user.uid, 'data', 'profile');
  const profileSnap = await getDoc(profileRef);
  if (!profileSnap.exists()) {
    await setDoc(profileRef, {
      email: cred.user.email,
      displayName: cred.user.displayName || '',
      company: '',
      wizardCompleted: false,
      createdAt: serverTimestamp(),
    });
  }
  return { uid: cred.user.uid, email: cred.user.email, displayName: cred.user.displayName };
}

async function logout() {
  if (!auth) return;
  await signOut(auth);
  currentUser = null;
}

function getUser() {
  if (!currentUser) return null;
  return { uid: currentUser.uid, email: currentUser.email, displayName: currentUser.displayName };
}

/* ── Cloud Data CRUD ──────────────────────────────────────────────── */

// Document paths: users/{uid}/data/{docName}
// docName: 'profile', 'settings', 'smtp'

async function cloudGet(docName) {
  if (!currentUser || !db) return null;
  const ref = doc(db, 'users', currentUser.uid, 'data', docName);
  const snap = await getDoc(ref);
  return snap.exists() ? snap.data() : null;
}

async function cloudSet(docName, data) {
  if (!currentUser || !db) return;
  const ref = doc(db, 'users', currentUser.uid, 'data', docName);
  await setDoc(ref, { ...data, updatedAt: serverTimestamp() }, { merge: true });
}

// Collection paths: users/{uid}/collections/{collectionName}/{docId}

async function cloudListCollection(collectionName) {
  if (!currentUser || !db) return [];
  const colRef = collection(db, 'users', currentUser.uid, 'collections', collectionName);
  // Firestore doesn't allow getting a subcollection of a non-existing doc this way
  // Use: users/{uid}/{collectionName}
  const colRef2 = collection(db, 'users', currentUser.uid, collectionName);
  const snap = await getDocs(colRef2);
  return snap.docs.map((d) => ({ id: d.id, ...d.data() }));
}

async function cloudAddToCollection(collectionName, docId, data) {
  if (!currentUser || !db) return;
  const ref = doc(db, 'users', currentUser.uid, collectionName, docId);
  await setDoc(ref, { ...data, updatedAt: serverTimestamp() });
}

async function cloudDeleteFromCollection(collectionName, docId) {
  if (!currentUser || !db) return;
  const ref = doc(db, 'users', currentUser.uid, collectionName, docId);
  await deleteDoc(ref);
}

async function cloudSetCollection(collectionName, items) {
  // Replace entire collection: delete all, write new items
  if (!currentUser || !db) return;
  const colRef = collection(db, 'users', currentUser.uid, collectionName);
  const snap = await getDocs(colRef);
  // Delete existing
  for (const d of snap.docs) {
    await deleteDoc(d.ref);
  }
  // Write new
  for (const item of items) {
    const id = item.id || require('crypto').randomUUID();
    const ref = doc(db, 'users', currentUser.uid, collectionName, id);
    await setDoc(ref, { ...item, updatedAt: serverTimestamp() });
  }
}

/* ── Full sync helpers ────────────────────────────────────────────── */

async function syncUpload(localData) {
  // localData: { settings, signatures, recents, smtp, profile }
  if (!currentUser || !db) return { ok: false, error: 'Not logged in' };
  try {
    if (localData.profile)    await cloudSet('profile', localData.profile);
    if (localData.settings)   await cloudSet('settings', localData.settings);
    if (localData.smtp)       await cloudSet('smtp', localData.smtp);
    if (localData.signatures) await cloudSetCollection('signatures', localData.signatures);
    if (localData.recents)    await cloudSetCollection('recents', localData.recents);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

async function syncDownload() {
  if (!currentUser || !db) return { ok: false, error: 'Not logged in' };
  try {
    const profile    = await cloudGet('profile');
    const settings   = await cloudGet('settings');
    const smtp       = await cloudGet('smtp');
    const signatures = await cloudListCollection('signatures');
    const recents    = await cloudListCollection('recents');
    return { ok: true, data: { profile, settings, smtp, signatures, recents } };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

module.exports = {
  init,
  isConfigured,
  register,
  login,
  loginWithGoogleToken,
  logout,
  getUser,
  cloudGet,
  cloudSet,
  cloudListCollection,
  cloudAddToCollection,
  cloudDeleteFromCollection,
  cloudSetCollection,
  syncUpload,
  syncDownload,
};
