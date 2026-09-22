package br.com.conde.bytecode.compilador;

import java.util.ArrayList;
import java.util.List;

/**
 * O lexer: de texto para símbolos.
 *
 * <p>É a parte mais simples e a que mais engole erro em silêncio quando feita
 * às pressas. Aqui todo caractere que não é reconhecido vira uma exceção com a
 * posição, em vez de ser pulado — um {@code 2 $ 3} tem que reclamar do
 * cifrão, não calcular 2 e ignorar o resto.
 */
public final class Lexer {

  private Lexer() {}

  /** Quebra o texto em símbolos, terminando sempre com FIM. */
  public static List<Simbolo> analisar(String fonte) {
    List<Simbolo> simbolos = new ArrayList<>();
    int i = 0;

    while (i < fonte.length()) {
      char c = fonte.charAt(i);

      if (Character.isWhitespace(c)) {
        i += 1;
        continue;
      }

      if (Character.isDigit(c) || (c == '.' && i + 1 < fonte.length() && Character.isDigit(fonte.charAt(i + 1)))) {
        int inicio = i;

        while (i < fonte.length() && (Character.isDigit(fonte.charAt(i)) || fonte.charAt(i) == '.')) {
          i += 1;
        }

        // Notação científica: 1e-3, 2.5E+10.
        if (i < fonte.length() && (fonte.charAt(i) == 'e' || fonte.charAt(i) == 'E')) {
          int marca = i;

          i += 1;

          if (i < fonte.length() && (fonte.charAt(i) == '+' || fonte.charAt(i) == '-')) {
            i += 1;
          }

          if (i < fonte.length() && Character.isDigit(fonte.charAt(i))) {
            while (i < fonte.length() && Character.isDigit(fonte.charAt(i))) {
              i += 1;
            }
          } else {
            // Era um nome começando com "e", não um expoente.
            i = marca;
          }
        }

        String texto = fonte.substring(inicio, i);

        try {
          Double.parseDouble(texto);
        } catch (NumberFormatException erro) {
          throw new ErroDeExpressao("Número malformado: " + texto, inicio);
        }

        simbolos.add(new Simbolo(Simbolo.Tipo.NUMERO, texto, inicio));
        continue;
      }

      if (Character.isLetter(c) || c == '_') {
        int inicio = i;

        while (i < fonte.length() && (Character.isLetterOrDigit(fonte.charAt(i)) || fonte.charAt(i) == '_')) {
          i += 1;
        }

        simbolos.add(new Simbolo(Simbolo.Tipo.NOME, fonte.substring(inicio, i), inicio));
        continue;
      }

      Simbolo.Tipo tipo =
          switch (c) {
            case '(' -> Simbolo.Tipo.ABRE;
            case ')' -> Simbolo.Tipo.FECHA;
            case ',' -> Simbolo.Tipo.VIRGULA;
            case '+', '-', '*', '/', '%', '^' -> Simbolo.Tipo.OPERADOR;
            default -> throw new ErroDeExpressao("Caractere inesperado: '" + c + "'", i);
          };

      simbolos.add(new Simbolo(tipo, String.valueOf(c), i));
      i += 1;
    }

    simbolos.add(new Simbolo(Simbolo.Tipo.FIM, "", fonte.length()));

    return simbolos;
  }
}
