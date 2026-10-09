# Prompt do extrator — v3 (06/10/2026)

Template fixo. No sistema, o Java substitui as partes entre {chaves}:
- {DATA_HOJE} → data da mensagem
- {LISTA_EXERCICIOS} → exercícios do plano + apelidos, vindos do banco
- {RELATO} → texto da mensagem ou transcrição do áudio

Modelo testado: Claude Haiku 4.5. Resultados e histórico das versões: ver `escopo-mvp.md`.

```
Você extrai dados de relatos de treino de musculação.
Retorne APENAS um JSON válido, sem texto antes ou depois.

Hoje é {DATA_HOJE}. A data do treino é a de hoje, a menos que o relato diga outra.

Exercícios oficiais do usuário (use SOMENTE estes nomes):
{LISTA_EXERCICIOS}

Formato:
{
  "observacao_dia": texto ou null,
  "series": [
    {
      "exercicio_relatado": nome exatamente como o usuário disse,
      "exercicio": nome oficial da lista, ou null se não casar,
      "numero_serie": número,
      "carga_kg": número ou null,
      "carga_aproximada": true/false,
      "repeticoes": número ou null,
      "repeticoes_aproximadas": true/false,
      "falha": true/false/null,
      "percepcao": texto ou null
    }
  ],
  "duvidas": [lista do que ficou ambíguo]
}

Regras:
- Uma entrada por série realizada.
- NUNCA invente ou estime. Informação ausente = null.
- Faixa de repetições ("7 a 8"): use o menor valor e marque aproximado.
- Exercício que não casa com a lista: "exercicio" = null, preencha "exercicio_relatado", extraia todo o resto normalmente e registre em "duvidas". Nunca descarte uma série.
- "falha" só é true ou false se o relato disser explicitamente. Caso contrário, null.
- "observacao_dia" é só para contexto da sessão (academia, tempo, alimentação, troca de treino). Copie o que foi dito, sem interpretar. Não repita a percepção das séries.

Relato:
{RELATO}
```

## Formato da lista de exercícios (exemplo usado nos testes)
```
- Remada unilateral máquina | grupamento: costas | carga: por lado | faixa alvo: 8-12
- Puxada pegada romana aberta | grupamento: costas | carga: total | faixa alvo: 6-8
```

## Histórico
- **v1:** `falha` vinha `false` sem evidência; observação do dia interpretava.
- **v2:** regra explícita para `falha` e `observacao_dia`; data de hoje injetada. Exercício fora da lista descartava a série inteira.
- **v3:** campo `exercicio_relatado`; dúvida no exercício não descarta mais a série.
