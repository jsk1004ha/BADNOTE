# Bad Note 3.3.26 - Correções de Tradução e Bugs (PT-BR)
**Autor:** [dyduq12](https://github.com/dyduq12)

Esta versão introduz uma série de melhorias arquiteturais, correções de tradução (i18n) e ajustes de UI específicos para melhorar a experiência em Português.

## 🛠 Modificações e Melhorias

### 1. Refatoração de Internacionalização (i18n)
- A lógica de tradução do `app.js` foi revisada com melhor tratamento para interpolação de strings.
- O sistema de tradução passou a não quebrar chaves de objetos, tags HTML, variáveis (`${...}`) ou concatenações.
- As traduções faltantes foram totalmente inseridas em `pt.js`, corrigindo a regressão para o Coreano.

### 2. Aprimoramentos do Motor de OCR
- O arquivo `MainActivity.java` no Android continua a puxar o fallback correto caso necessário, e agora a UI está alinhada para invocar os pacotes de idioma corretos de OCR para o Português.
- O cálculo matemático (`O calculo automatico sumiu`) voltou a funcionar através da correção do script de inicialização do Math Engine, que falhava porque strings chaves de OCR estavam destraduzidas.

### 3. Ajustes Visuais e Paleta de Cores (UI/UX)
- Corrigido o erro crasso na Paleta de Cores (`não abre ela`). O bug ocorria porque o seletor `$('#colorPickerBtn')` procurava textos alterados e perdia a referência DOM. Nenhuma tradução chave do core component foi danificada.
- Abaixamos um pouco o **Botão de Desfazer**, que estava sobrepondo a "linha azul" visual na tela principal do tablet.
- Os textos do painel da SPen (canto inferior esquerdo) foram propriamente mapeados e traduzidos sem esticar o limite de tela.

### 4. Processo de Deploy e Sincronização Android
- Por que as alterações não eram salvas no PC? A pasta `web/` raiz atua apenas como fonte. O Webview nativo consome `android/app/src/main/assets/public/`.
- Foi criado o `copy_script.ps1` que sincroniza a pasta raiz com o container Android. Agora toda mudança Web é refletida no APK!

## 🚀 Como Manter o Projeto (Guia Rápido)
1. **Novas Traduções**: Identifique o texto cru (em coreano) na tela. Abra `web/locales/pt.js`, crie uma chave com a exata string (incluindo quebras de linha ou variáveis JSON) e coloque a tradução no valor. O `loadTranslations` fará o match automático.
2. **Recompilação Exata**:
   - Faça as mudanças no `web/` ou `docs/` etc.
   - Execute o script de cópia: `powershell -ExecutionPolicy Bypass -File copy_script.ps1`
   - Abra o Android Studio ou o console e execute o build.

Agradecimento especial ao dev original e à comunidade!
