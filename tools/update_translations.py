import re
import time
from pathlib import Path

try:
    from deep_translator import GoogleTranslator
except ImportError:
    print("Please install deep-translator: pip install deep-translator")
    exit(1)

REPO_ROOT = Path(__file__).resolve().parents[1]
WEB_DIR = REPO_ROOT / 'web'
APP_JS = WEB_DIR / 'app.js'
INDEX_HTML = WEB_DIR / 'index.html'
LOCALES_DIR = WEB_DIR / 'locales'

LANGUAGES = {
    'en': 'I18N_EN',
    'ja': 'I18N_JA',
    'zh': 'I18N_ZH',
    'pt': 'I18N_PT',
    'ko': 'I18N_KO'
}

def extract_strings_from_files():
    korean_re = re.compile(r'[\uAC00-\uD7AF]')
    strings = set()
    
    files_to_scan = [INDEX_HTML]
    for filepath in WEB_DIR.glob('*.js'):
        files_to_scan.append(filepath)
            
    for filepath in files_to_scan:
        with filepath.open('r', encoding='utf-8') as f:
            content = f.read()
        
        matches = re.findall(r"'([^'\n]+)'", content)
        matches += re.findall(r'"([^"\n]+)"', content)
        matches += re.findall(r'`([^`]+)`', content)
        matches += re.findall(r'>\s*([^<\n]+?)\s*<', content)
        
        for m in matches:
            m = m.strip()
            if not korean_re.search(m):
                continue
            if len(m) > 250:
                continue
            if 'function' in m or '=>' in m or 'break;' in m or 'continue;' in m:
                continue
            if m.startswith('$('):
                continue
            strings.add(m)
            
    return sorted(list(strings))

def parse_locale_file(filepath, var_name):
    if not filepath.exists():
        return {}
    with filepath.open('r', encoding='utf-8') as f:
        content = f.read()
    match = re.search(r'window\.' + var_name + r'\s*=\s*(\{.*?\});', content, re.DOTALL)
    if match:
        try:
            # simple string replacement to make it valid json
            json_str = match.group(1)
            # Remove trailing commas
            json_str = re.sub(r',\s*\}', '}', json_str)
            # This is risky, but works if keys and values are double quoted
            # Actually, let's just parse it line by line to build a dict
            pass
        except Exception:
            pass

    # Safe fallback parser:
    res = {}
    lines = content.split('\n')
    for line in lines:
        line = line.strip()
        if line.startswith('"') and '": "' in line:
            parts = line.split('": "', 1)
            key = parts[0][1:]
            val = parts[1].rsplit('"', 1)[0]
            # unescape
            key = key.replace('\\"', '"').replace('\\n', '\n')
            val = val.replace('\\"', '"').replace('\\n', '\n')
            res[key] = val
    return res

def save_locale_file(filepath, var_name, locale_dict):
    with filepath.open('w', encoding='utf-8') as f:
        if var_name == "I18N_PT":
            f.write("// Translation and UI optimizations for PT-BR by @dyduq12\n")
        f.write(f"window.{var_name} = {{\n")
        items = []
        for k, v in locale_dict.items():
            k_esc = k.replace('"', '\\"').replace('\n', '\\n')
            v_esc = v.replace('"', '\\"').replace('\n', '\\n')
            items.append(f'  "{k_esc}": "{v_esc}"')
        f.write(",\n".join(items))
        f.write("\n};\n")

def generate_locales():
    LOCALES_DIR.mkdir(parents=True, exist_ok=True)
        
    all_strings = extract_strings_from_files()
    print(f"Total unique Korean strings extracted: {len(all_strings)}")
    
    # Pre-initialize translators
    translators = {
        'en': GoogleTranslator(source='ko', target='en'),
        'pt': GoogleTranslator(source='ko', target='pt'),
        'ja': GoogleTranslator(source='ko', target='ja'),
        'zh': GoogleTranslator(source='ko', target='zh-CN')
    }
    
    for lang, var_name in LANGUAGES.items():
        print(f"\\nProcessing {lang}...")
        filepath = LOCALES_DIR / f"{lang}.js"
        locale_dict = parse_locale_file(filepath, var_name)
        
        added = 0
        for s in all_strings:
            s_clean = s.replace('"', '\\"').replace('\n', '\\n')
            if s_clean not in locale_dict or locale_dict[s_clean] == "":
                if lang == 'ko':
                    locale_dict[s_clean] = s
                else:
                    try:
                        translated = translators[lang].translate(s)
                        locale_dict[s_clean] = translated
                        time.sleep(0.05)
                    except Exception as e:
                        print(f"Error: {s} --> {e}")
                        locale_dict[s_clean] = "TODO_" + s_clean
                added += 1
                
        print(f"Added {added} new translations for {lang}")
        save_locale_file(filepath, var_name, locale_dict)

if __name__ == '__main__':
    generate_locales()
