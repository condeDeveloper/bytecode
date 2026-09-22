package br.com.conde.bytecode.compilador;

import br.com.conde.bytecode.CarregadorEmMemoria;
import br.com.conde.bytecode.Expressao;
import br.com.conde.bytecode.classe.Codigo;
import br.com.conde.bytecode.classe.EscritorDeClasse;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O compilador: de árvore para bytecode.
 *
 * <p>Percorrer a árvore em pós-ordem e emitir uma instrução por nó <b>já é</b>
 * gerar código para uma máquina de pilha. Não há alocação de registradores,
 * não há seleção de instrução: a forma da árvore é a ordem das instruções.
 * {@code (2 + 3) * 4} vira, literalmente:
 *
 * <pre>
 *   ldc2_w 2.0
 *   ldc2_w 3.0
 *   dadd
 *   ldc2_w 4.0
 *   dmul
 *   dreturn
 * </pre>
 *
 * <p>É por isso que a JVM foi desenhada como máquina de pilha em 1995: o
 * compilador fica trivial e o bytecode fica compacto. O preço é que a
 * execução direta é lenta — e é aí que entra o JIT, que refaz o caminho
 * inverso e volta para registradores de verdade.
 */
public final class Compilador {

  /** Descritor do método gerado: recebe um vetor de double e devolve double. */
  public static final String DESCRITOR = "([D)D";

  private static final AtomicLong CONTADOR = new AtomicLong();

  private final Codigo codigo;

  private Compilador(Codigo codigo) {
    this.codigo = codigo;
  }

  /** Compila uma expressão e devolve o objeto pronto para usar. */
  public static Compilada compilar(String fonte) {
    return compilar(fonte, new CarregadorEmMemoria());
  }

  /** O mesmo, com um carregador escolhido por quem chama. */
  public static Compilada compilar(String fonte, CarregadorEmMemoria carregador) {
    Analisador.Resultado analise = Analisador.analisar(fonte);
    String nome = "br.com.conde.bytecode.gerado.Expressao$" + CONTADOR.incrementAndGet();

    byte[] bytes = montarClasse(nome, analise.raiz());

    Class<?> classe = carregador.definir(nome, bytes);

    try {
      Expressao expressao = (Expressao) classe.getDeclaredConstructor().newInstance();

      return new Compilada(expressao, analise.variaveis(), bytes, fonte);
    } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException erro) {
      throw new IllegalStateException("A classe gerada não pôde ser instanciada", erro);
    }
  }

  /** Só os bytes, sem carregar — útil para gravar num arquivo e rodar o javap. */
  public static byte[] montarClasse(String nomeJava, String fonte) {
    return montarClasse(nomeJava, Analisador.analisar(fonte).raiz());
  }

  private static byte[] montarClasse(String nomeJava, No raiz) {
    EscritorDeClasse escritor = new EscritorDeClasse(nomeJava);

    escritor.implementa(Expressao.class.getName());
    escritor.construtorVazio();

    Codigo codigo = escritor.metodo(EscritorDeClasse.PUBLICA, "calcular", DESCRITOR);

    new Compilador(codigo).emitir(raiz);
    codigo.retornarDouble();

    return escritor.bytes();
  }

  private void emitir(No no) {
    switch (no) {
      case No.Constante constante -> codigo.constante(constante.valor());

      case No.Variavel variavel -> {
        // A posição 0 é o `this`, a 1 é o vetor de variáveis.
        codigo.carregarReferencia(1);
        codigo.inteiro(variavel.indice());
        codigo.lerDoVetorDeDouble();
      }

      case No.Negacao negacao -> {
        emitir(negacao.dentro());
        codigo.negar();
      }

      case No.Binario binario -> {
        emitir(binario.esquerda());
        emitir(binario.direita());

        switch (binario.operador()) {
          case '+' -> codigo.somar();
          case '-' -> codigo.subtrair();
          case '*' -> codigo.multiplicar();
          case '/' -> codigo.dividir();
          case '%' -> codigo.resto();
          // A potência não tem instrução: é uma chamada a Math.pow.
          case '^' -> codigo.chamarEstatico("java/lang/Math", "pow", "(DD)D", 4, 2);
          default -> throw new IllegalStateException("Operador sem emissão: " + binario.operador());
        }
      }

      case No.Chamada chamada -> {
        for (No argumento : chamada.argumentos()) {
          emitir(argumento);
        }

        emitirChamada(chamada.nome(), chamada.argumentos().size());
      }
    }
  }

  private void emitirChamada(String nome, int argumentos) {
    String metodo =
        switch (nome) {
          case "raiz" -> "sqrt";
          case "abs" -> "abs";
          case "piso" -> "floor";
          case "teto" -> "ceil";
          case "arredondar" -> "rint";
          case "sen" -> "sin";
          case "cos" -> "cos";
          case "tan" -> "tan";
          case "log" -> "log";
          case "exp" -> "exp";
          case "min" -> "min";
          case "max" -> "max";
          case "potencia" -> "pow";
          default -> throw new IllegalStateException("Função sem emissão: " + nome);
        };

    String descritor = argumentos == 2 ? "(DD)D" : "(D)D";

    codigo.chamarEstatico("java/lang/Math", metodo, descritor, argumentos * 2, 2);
  }

  /** O resultado da compilação. */
  public record Compilada(Expressao expressao, List<String> variaveis, byte[] bytes, String fonte) {

    /** Calcula passando as variáveis na ordem em que foram declaradas. */
    public double calcular(double... valores) {
      if (valores.length < variaveis.size()) {
        throw new IllegalArgumentException(
            "A expressão usa " + variaveis.size() + " variável(is) (" + String.join(", ", variaveis)
                + ") e vieram " + valores.length);
      }

      return expressao.calcular(valores);
    }
  }
}
