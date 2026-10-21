# Free My TV — TCL Performance Fork

Fork do [Free My TV de simbasjungles](https://github.com/simbasjungles/free-my-tv), adaptado e otimizado em uma TCL com Android 11. Mantém o launcher visual com dock, transições, clima e recomendações, com otimizações de renderização e uma aba de futebol experimental.

## Resultados registrados na nossa TCL

**Quadros com engasgos: 49,84% → 1,02%. Tempo de quadro p95: 40 → 10 ms.**

| Métrica | Antes da otimização local | Depois |
|---|---:|---:|
| Quadros janky | 49,84% | 1,02% |
| Tempo de quadro p50 / p95 | 16 / 40 ms | 6 / 10 ms |
| Cache GPU informado pelo gfxinfo | 92,72 MB | 2,84 MB |
| PSS do processo Home | 321.935 KiB | 117.828 KiB |

[Metodologia, limitações e logs brutos](benchmarks/tcl-android11/README.md). São duas amostras históricas da mesma TV, antes/depois das nossas otimizações. **Não é uma comparação contra a versão atual do upstream**, nem uma promessa de consumo zero ou ausência total de travamentos. Os hashes dos dois APKs medidos não foram registrados.

## O que há nesta versão

- Compatibilidade Android 11/API 30 e retorno ao launcher pelo botão Home usando serviço de acessibilidade restrito aos launchers do sistema.
- Sombras cacheadas, escala de texto por matriz, desenho dividido em RenderNodes e quadros solicitados sob demanda para reduzir trabalho nas animações.
- Dock e atalhos para streaming, incluindo SmartTube quando instalado; clima e recomendações TMDB configuráveis.
- Aba **Flamengo experimental**: jogos, resultados, tabela e links de melhores momentos. Dados via ESPN e busca/abertura no YouTube. A seleção de outros times ainda não está implementada.
- Interface do launcher sem publicidade, herdada do projeto original.

### Bloqueio de anúncios na TV

Nesta instalação foi usado **DNS privado AdGuard**, uma configuração opcional do Android, separada do APK: modo `hostname`, servidor `dns.adguard-dns.com`. O fork não ativa isso automaticamente. Filtragem DNS bloqueia domínios de anúncios/rastreamento; não remove todos os anúncios e pode afetar serviços. Não substitui o SmartTube nem garante bloqueio de anúncios dentro de todos os streamings.

Referências: [AdGuard DNS público](https://adguard-dns.io/en/public-dns.html), [como a filtragem DNS funciona](https://adguard-dns.io/kb/general/dns-filtering/), [SmartTube](https://github.com/yuliskov/SmartTube). SmartTube é um aplicativo separado, não incluído neste APK.

## Compilar e instalar

Pré-requisitos: JDK 17+, Android SDK com build-tools 34+ e plataforma android-34, adb. Defina `JAVA_HOME` e `ANDROID_HOME` se necessário.

```bash
cp config.example.properties config.properties
# Edite config.properties: sua cidade, região, idioma e chave TMDB opcional.
bash build.sh
adb connect <IP_DA_TV>:5555
adb install -r FreeMyTV.apk
```

Aceite a autorização do computador na TV. Em Android 11, abra as configurações do launcher e habilite o serviço de acessibilidade quando solicitado para manter o botão Home. Os caminhos variam por fabricante. Leia [as instruções herdadas](README.upstream.md) e confira o modo de recuperação antes de substituir seu launcher.

`config.properties`, APKs gerados e chaves de assinatura ficam fora do Git. A chave TMDB é embutida no seu APK ao compilar: evite distribuir builds pessoais com credenciais. Guarde seu `freemytv.keystore` local, necessário para instalar atualizações. O pacote continua `io.freemytv`; builds com assinaturas diferentes não atualizam uma instalação existente diretamente.

## Origem e estado

Ramo de adaptação baseado no commit upstream `501bc3c1396604d4027067575dc9934fda418de6`. O upstream avançou desde essa base; este ramo ainda não incorpora essas mudanças. O site em `docs/` e `README.upstream.md` são materiais herdados e podem descrever recursos/requisitos diferentes deste ramo. Publicação automática do site foi removida para não republicar o site original.

Nenhuma alteração de firmware, root ou promessa de desempenho de Apple TV 4K. A TCL continua limitada pelo hardware. A integração de controle Mac/AirPlay usada na TV é um projeto separado.

## Créditos e licença

Projeto original: [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv). Adaptação TCL, otimizações e personalizações locais: André Cruz, com assistência de Codex e Gemini. Código sob [MIT](LICENSE); demais créditos em [README.upstream.md](README.upstream.md#credits). Marcas, escudos e logotipos pertencem aos respectivos titulares.

Fontes de dados: TMDB, Open-Meteo, Binance e ESPN; links de vídeos abrem o YouTube. Este produto usa a API TMDB, mas não é endossado nem certificado pelo TMDB. Não é afiliado a Google, TCL, ESPN ou clubes.
