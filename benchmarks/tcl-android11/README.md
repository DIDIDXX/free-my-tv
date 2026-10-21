# TCL Android 11: medições históricas

Uma execução antes e uma depois das otimizações locais, na mesma TCL com Android 11/API 30 e 2 GB de RAM. Modelo comercial não confirmado. Sequência: foco no dock, abrir/navegar/fechar gaveta, cartões de recomendações e abas superiores. A coleta original usou `dumpsys gfxinfo io.freemytv:home`; PSS vem de fotografias separadas de `dumpsys meminfo io.freemytv:home` após a navegação.

| Métrica | Antes | Depois |
|---|---:|---:|
| Quadros renderizados | 943 | 976 |
| Quadros janky | 470 (49,84%) | 10 (1,02%) |
| Tempo de quadro p50 | 16 ms | 6 ms |
| Tempo de quadro p95 | 40 ms | 10 ms |
| Tempo de quadro p99 | 48 ms | 18 ms |
| Cache GPU total (rótulo MB do gfxinfo) | 92,72 MB | 2,84 MB |
| PSS do processo Home (KiB) | 321.935 | 117.828 |

## Escopo da comparação

Os logs foram herdados da sessão local de otimização. Não registraram hashes do APK/fonte de cada execução, temperatura, resolução ou configuração de alimentação. A base de código usada na adaptação veio do upstream `501bc3c1396604d4027067575dc9934fda418de6`, mas o “antes” já é uma adaptação local; não representa o APK upstream intacto. O APK original desse commit exige API 31 e não instala nessa TV API 30.

Portanto, esta tabela descreve **antes/depois da otimização local**, não uma disputa controlada contra o upstream mais recente. Em 21/10/2026, o upstream já continha mudanças adicionais, incluindo sombras cacheadas em `Shadows.java`. Nenhuma medição aqui prova superioridade sobre essas mudanças.

São amostras únicas, com contagens de quadros diferentes; não há intervalo de confiança, medição de consumo elétrico ou garantia de ausência de travamentos. PSS é memória proporcional do processo, não RAM livre do aparelho; cache GPU não é toda a memória da GPU. O bucket GPU 4950 ms do log anterior não deve ser interpretado como latência literal confiável. O contador “High input latency” continuou alto (680 → 661).

## Repetir a sequência

```bash
adb connect <IP_DA_TV>:5555
ANDROID_SERIAL=<IP_DA_TV>:5555 bash benchmarks/benchmark-nav.sh > resultado-gfxinfo.txt
adb -s <IP_DA_TV>:5555 shell dumpsys meminfo io.freemytv:home > resultado-meminfo.txt
```

Comece na mesma tela, com dock em foco, mesma seleção e conteúdo já carregado. Mantenha resolução, aplicativos, rede, brilho e condições térmicas iguais. Registre commit, SHA-256 do APK e características do dispositivo; faça várias execuções por versão. A sequência envia teclas e altera o foco da TV. Não instala APKs, não muda a depuração e não configura DNS.

## O que foi otimizado

- Sombras em bitmaps cacheados em meia resolução em vez de variar máscaras de blur a cada quadro.
- Escala de texto por matriz do Canvas em vez de gerar glifos em vários tamanhos intermediários.
- RenderNodes por região, bitmaps arredondados previamente e reutilização de objetos de desenho.
- Solicitação de quadros conforme animações/transições precisam, evitando redesenho contínuo quando a tela está parada. Isso não significa CPU/GPU do aparelho em zero: clima, rede e outros serviços continuam ativos.
