import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zh from './locales/zh.json';
import en from './locales/en.json';
import zhTW from './locales/zh-TW.json';
import hi from './locales/hi.json';
import es from './locales/es.json';
import fr from './locales/fr.json';
import ja from './locales/ja.json';
import ru from './locales/ru.json';
import ko from './locales/ko.json';

const DEFAULT_LANGUAGE = 'zh';
const SUPPORTED_LANGUAGES = ['zh', 'en', 'zh-TW', 'hi', 'es', 'fr', 'ja', 'ru', 'ko'];

// Retrieve the saved language from localStorage only when the user explicitly set it.
// Auto-detected IDEA locale values are intentionally ignored so new windows default to Chinese.
const getInitialLanguage = (): string => {
  const manuallySet = localStorage.getItem('languageManuallySet') === 'true';
  const savedLanguage = localStorage.getItem('language');
  return manuallySet && savedLanguage && SUPPORTED_LANGUAGES.includes(savedLanguage)
    ? savedLanguage
    : DEFAULT_LANGUAGE;
};

i18n
  .use(initReactI18next) // Integrate i18n with React
  .init({
    resources: {
      zh: { translation: zh }, // Simplified Chinese
      en: { translation: en }, // English
      'zh-TW': { translation: zhTW }, // Traditional Chinese
      hi: { translation: hi }, // Hindi
      es: { translation: es }, // Spanish
      fr: { translation: fr }, // French
      ja: { translation: ja }, // Japanese
      ru: { translation: ru }, // Russian
      ko: { translation: ko }, // Korean
    },
    lng: getInitialLanguage(), // Initial language
    fallbackLng: 'zh', // Fallback to Simplified Chinese when a translation is missing
    interpolation: {
      escapeValue: false, // React already handles XSS protection
    },
  });

export default i18n;
