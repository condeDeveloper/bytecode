package br.com.conde.bytecode.compilador;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * O analisador de expressões: de texto para árvore.
 *
 * <p>Descida recursiva, uma função por nível de precedência. A ordem das
 * funções <b>é</b> a tabela de precedência, o que torna a gramática legível
 * sem consultar documentação:
 *
 * <pre>
 *   expressão := termo   (('+' | '-') termo)*
 *   termo     := unário  (('*' | '/' | '%') unário)*
 *   unário    := '-' unário | potência
 *   potência  := primário ('^' unário)?      ← à direita
 *   primário  := número | variável | função '(' args ')' | '(' expressão ')'
 * </pre>
 *
 * <p>Dois detalhes que valem o comentário:
 *
 * <ul>
 *   <li>A potência associa <b>à direita</b>: {@code 2^3^2} é 2^(3^2) = 512, e
 *       não (2^3)^2 = 64.
 *   <li>O menos unário une <b>mais fraco</b> que a potência: {@code -2^2} é
 *       −(2²) = −4, como em matemática, em Java e em Python. Planilha faz o
 *       contrário e dá 4 — é a divergência mais famosa entre as duas
 *       convenções, e por isso ela precisa estar escrita em algum lugar.
 * </ul>
 */
public final class Analisador {

  /** Funções que viram uma chamada a {@code java.lang.Math}. */
  public static final Set<String> FUNCOES =
      Set.of("raiz", "abs", "piso", "teto", "arredondar", "sen", "cos", "tan", "log", "exp", "min", "max", "potencia");

  private final List<Simbolo> simbolos;
  private final List<String> variaveis = new ArrayList<>();
  private int i;

  private Analisador(List<Simbolo> simbolos) {
    this.simbolos = simbolos;
  }

  /** Analisa uma expressão e devolve a árvore e as variáveis encontradas. */
  public static Resultado analisar(String fonte) {
    Analisador analisador = new Analisador(Lexer.analisar(fonte));
    No raiz = analisador.expressao();

    analisador.exigir(Simbolo.Tipo.FIM, "Sobrou conteúdo depois da expressão");

    return new Resultado(raiz, List.copyOf(analisador.variaveis));
  }

  /** A árvore e os nomes das variáveis, na ordem em que apareceram. */
  public record Resultado(No raiz, List<String> variaveis) {}

  private Simbolo atual() {
    return simbolos.get(i);
  }

  private boolean aceitar(Simbolo.Tipo tipo, String texto) {
    if (atual().tipo() == tipo && (texto == null || atual().texto().equals(texto))) {
      i += 1;

      return true;
    }

    return false;
  }

  private Simbolo exigir(Simbolo.Tipo tipo, String mensagem) {
    if (atual().tipo() != tipo) {
      throw new ErroDeExpressao(mensagem + ", veio " + atual().descricao(), atual().posicao());
    }

    return simbolos.get(i++);
  }

  private No expressao() {
    No esquerda = termo();

    for (; ; ) {
      if (aceitar(Simbolo.Tipo.OPERADOR, "+")) {
        esquerda = new No.Binario('+', esquerda, termo());
      } else if (aceitar(Simbolo.Tipo.OPERADOR, "-")) {
        esquerda = new No.Binario('-', esquerda, termo());
      } else {
        return esquerda;
      }
    }
  }

  private No termo() {
    No esquerda = unario();

    for (; ; ) {
      if (aceitar(Simbolo.Tipo.OPERADOR, "*")) {
        esquerda = new No.Binario('*', esquerda, unario());
      } else if (aceitar(Simbolo.Tipo.OPERADOR, "/")) {
        esquerda = new No.Binario('/', esquerda, unario());
      } else if (aceitar(Simbolo.Tipo.OPERADOR, "%")) {
        esquerda = new No.Binario('%', esquerda, unario());
      } else {
        return esquerda;
      }
    }
  }

  private No unario() {
    if (aceitar(Simbolo.Tipo.OPERADOR, "-")) {
      return new No.Negacao(unario());
    }

    if (aceitar(Simbolo.Tipo.OPERADOR, "+")) {
      return unario();
    }

    return potencia();
  }

  private No potencia() {
    No base = primario();

    // À direita: o lado direito volta para `unario`, não para `potencia`.
    if (aceitar(Simbolo.Tipo.OPERADOR, "^")) {
      return new No.Binario('^', base, unario());
    }

    return base;
  }

  private No primario() {
    Simbolo simbolo = atual();

    if (aceitar(Simbolo.Tipo.NUMERO, null)) {
      return new No.Constante(Double.parseDouble(simbolo.texto()));
    }

    if (aceitar(Simbolo.Tipo.ABRE, null)) {
      No dentro = expressao();

      exigir(Simbolo.Tipo.FECHA, "Faltou fechar o parêntese");

      return dentro;
    }

    if (simbolo.tipo() == Simbolo.Tipo.NOME) {
      i += 1;

      if (aceitar(Simbolo.Tipo.ABRE, null)) {
        return chamada(simbolo);
      }

      return variavel(simbolo.texto());
    }

    throw new ErroDeExpressao("Esperava um número, uma variável ou um parêntese, veio " + simbolo.descricao(), simbolo.posicao());
  }

  private No chamada(Simbolo nome) {
    if (!FUNCOES.contains(nome.texto())) {
      throw new ErroDeExpressao(
          "Função desconhecida: " + nome.texto() + ". Conheço " + String.join(", ", FUNCOES.stream().sorted().toList()),
          nome.posicao());
    }

    List<No> argumentos = new ArrayList<>();

    if (!aceitar(Simbolo.Tipo.FECHA, null)) {
      do {
        argumentos.add(expressao());
      } while (aceitar(Simbolo.Tipo.VIRGULA, null));

      exigir(Simbolo.Tipo.FECHA, "Faltou fechar o parêntese da função " + nome.texto());
    }

    int esperados = nome.texto().equals("min") || nome.texto().equals("max") || nome.texto().equals("potencia") ? 2 : 1;

    if (argumentos.size() != esperados) {
      throw new ErroDeExpressao(
          nome.texto() + " recebe " + esperados + " argumento(s), veio " + argumentos.size(), nome.posicao());
    }

    return new No.Chamada(nome.texto(), List.copyOf(argumentos));
  }

  private No variavel(String nome) {
    int indice = variaveis.indexOf(nome);

    // A ordem de descoberta define a posição no vetor de entrada, e é ela que
    // o chamador precisa respeitar.
    if (indice < 0) {
      indice = variaveis.size();
      variaveis.add(nome);
    }

    return new No.Variavel(nome, indice);
  }
}
