package br.com.conde.bytecode.classe;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * O montador de bytecode de um método.
 *
 * <p>A JVM é uma máquina de pilha: não há registradores. {@code 2 + 3} vira
 * "empilha 2, empilha 3, soma" — e a soma consome os dois e deixa um.
 *
 * <p>A parte que quase ninguém sabe é que o atributo {@code Code} precisa
 * declarar <b>quanto a pilha cresce no máximo</b>. Não é uma dica: o
 * verificador da JVM confere, e uma classe que declara menos do que usa é
 * recusada com {@code VerifyError} antes de executar uma instrução sequer.
 * Por isso esta classe conta a profundidade a cada instrução e guarda o pico.
 *
 * <p>Outra: {@code double} e {@code long} ocupam <b>duas</b> posições na pilha
 * e duas variáveis locais. Contar como um é o erro que gera o
 * {@code VerifyError} mais confuso que existe.
 */
public final class Codigo {

  // Os códigos de operação usados aqui. A lista inteira tem 200 e poucos.
  public static final int NOP = 0x00;
  public static final int ACONST_NULL = 0x01;
  public static final int ICONST_0 = 0x03;
  public static final int DCONST_0 = 0x0e;
  public static final int DCONST_1 = 0x0f;
  public static final int BIPUSH = 0x10;
  public static final int SIPUSH = 0x11;
  public static final int LDC = 0x12;
  public static final int LDC2_W = 0x14;
  public static final int ILOAD = 0x15;
  public static final int DLOAD = 0x18;
  public static final int ALOAD = 0x19;
  public static final int ALOAD_0 = 0x2a;
  public static final int DALOAD = 0x31;
  public static final int ISTORE = 0x36;
  public static final int DSTORE = 0x39;
  public static final int ASTORE = 0x3a;
  public static final int POP = 0x57;
  public static final int DUP = 0x59;
  public static final int DADD = 0x63;
  public static final int DSUB = 0x67;
  public static final int DMUL = 0x6b;
  public static final int DDIV = 0x6f;
  public static final int DREM = 0x73;
  public static final int DNEG = 0x77;
  public static final int I2D = 0x87;
  public static final int D2I = 0x8e;
  public static final int IRETURN = 0xac;
  public static final int DRETURN = 0xaf;
  public static final int ARETURN = 0xb0;
  public static final int RETURN = 0xb1;
  public static final int GETSTATIC = 0xb2;
  public static final int INVOKEVIRTUAL = 0xb6;
  public static final int INVOKESPECIAL = 0xb7;
  public static final int INVOKESTATIC = 0xb8;

  private final PoolDeConstantes pool;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final List<String> montagem = new ArrayList<>();

  private int pilhaAtual;
  private int pilhaMaxima;
  private int pilhaDeclarada = -1;
  private int variaveis;

  public Codigo(PoolDeConstantes pool, int variaveisIniciais) {
    this.pool = pool;
    this.variaveis = variaveisIniciais;
  }

  /** O bytecode montado. */
  public byte[] bytes() {
    return bytes.toByteArray();
  }

  /** O pico de profundidade da pilha, que vai no atributo Code. */
  public int pilhaMaxima() {
    return pilhaDeclarada >= 0 ? pilhaDeclarada : pilhaMaxima;
  }

  /**
   * Declara um valor de pilha diferente do que a conta deu.
   *
   * <p>Existe para mostrar o que acontece quando ele está errado: declarar
   * menos do que o código usa faz a JVM recusar a classe com
   * {@code VerifyError} <b>no carregamento</b>, antes de executar qualquer
   * coisa. É a diferença entre o verificador e um simples "confia em mim".
   */
  public Codigo declararPilhaMaxima(int valor) {
    this.pilhaDeclarada = valor;

    return this;
  }

  /** O pico que a conta deu, ignorando qualquer declaração forçada. */
  public int pilhaCalculada() {
    return pilhaMaxima;
  }

  /** Quantas variáveis locais o método usa, contando o {@code this}. */
  public int variaveis() {
    return variaveis;
  }

  /** A montagem em texto, que torna o bytecode legível num teste. */
  public List<String> montagem() {
    return List.copyOf(montagem);
  }

  /** Reserva espaço para uma variável local. */
  public int reservar(boolean ocupaDuas) {
    int indice = variaveis;

    variaveis += ocupaDuas ? 2 : 1;

    return indice;
  }

  private void empilhar(int quantidade) {
    pilhaAtual += quantidade;

    if (pilhaAtual > pilhaMaxima) {
      pilhaMaxima = pilhaAtual;
    }

    if (pilhaAtual < 0) {
      throw new IllegalStateException("A pilha ficou negativa: falta um valor antes desta instrução");
    }
  }

  private void emitir(String texto, int efeito, int... corpo) {
    montagem.add(texto);

    for (int valor : corpo) {
      bytes.write(valor & 0xFF);
    }

    empilhar(efeito);
  }

  private void emitirCurto(String texto, int opcode, int operando, int efeito) {
    emitir(texto, efeito, opcode, operando >> 8, operando);
  }

  /** Empilha um {@code double} constante. */
  public Codigo constante(double valor) {
    if (valor == 0.0 && Double.doubleToRawLongBits(valor) == 0L) {
      return emitirSimples("dconst_0", DCONST_0, 2);
    }

    if (valor == 1.0) {
      return emitirSimples("dconst_1", DCONST_1, 2);
    }

    // `ldc2_w` é o único jeito de trazer um double do pool, e ele empilha
    // dois lugares porque um double ocupa dois.
    emitirCurto("ldc2_w " + valor, LDC2_W, pool.duplo(valor), 2);

    return this;
  }

  /** Empilha um inteiro pequeno, do jeito mais curto que couber. */
  public Codigo inteiro(int valor) {
    if (valor >= 0 && valor <= 5) {
      return emitirSimples("iconst_" + valor, ICONST_0 + valor, 1);
    }

    if (valor >= Byte.MIN_VALUE && valor <= Byte.MAX_VALUE) {
      emitir("bipush " + valor, 1, BIPUSH, valor);

      return this;
    }

    if (valor >= Short.MIN_VALUE && valor <= Short.MAX_VALUE) {
      emitir("sipush " + valor, 1, SIPUSH, valor >> 8, valor);

      return this;
    }

    emitir("ldc " + valor, 1, LDC, pool.inteiro(valor));

    return this;
  }

  /** Empilha um literal de texto. */
  public Codigo texto(String valor) {
    emitir("ldc \"" + valor + "\"", 1, LDC, pool.texto(valor));

    return this;
  }

  private Codigo emitirSimples(String texto, int opcode, int efeito) {
    emitir(texto, efeito, opcode);

    return this;
  }

  /** Carrega uma referência de uma variável local. */
  public Codigo carregarReferencia(int slot) {
    if (slot == 0) {
      return emitirSimples("aload_0", ALOAD_0, 1);
    }

    emitir("aload " + slot, 1, ALOAD, slot);

    return this;
  }

  /** Carrega um {@code double} de uma variável local. */
  public Codigo carregarDouble(int slot) {
    emitir("dload " + slot, 2, DLOAD, slot);

    return this;
  }

  /** Guarda um {@code double} numa variável local. */
  public Codigo guardarDouble(int slot) {
    emitir("dstore " + slot, -2, DSTORE, slot);

    return this;
  }

  /** Lê {@code vetor[indice]} de um vetor de {@code double}. */
  public Codigo lerDoVetorDeDouble() {
    // Consome a referência e o índice (2) e devolve um double (2): saldo zero.
    return emitirSimples("daload", DALOAD, 0);
  }

  public Codigo somar() {
    return emitirSimples("dadd", DADD, -2);
  }

  public Codigo subtrair() {
    return emitirSimples("dsub", DSUB, -2);
  }

  public Codigo multiplicar() {
    return emitirSimples("dmul", DMUL, -2);
  }

  public Codigo dividir() {
    return emitirSimples("ddiv", DDIV, -2);
  }

  public Codigo resto() {
    return emitirSimples("drem", DREM, -2);
  }

  public Codigo negar() {
    return emitirSimples("dneg", DNEG, 0);
  }

  /** Chama um método estático. */
  public Codigo chamarEstatico(String classe, String nome, String descritor, int consome, int devolve) {
    emitirCurto(
        "invokestatic " + classe + "." + nome + descritor,
        INVOKESTATIC,
        pool.metodo(classe, nome, descritor),
        devolve - consome);

    return this;
  }

  /** Chama um construtor ou um método privado. */
  public Codigo chamarEspecial(String classe, String nome, String descritor, int consome, int devolve) {
    emitirCurto(
        "invokespecial " + classe + "." + nome + descritor,
        INVOKESPECIAL,
        pool.metodo(classe, nome, descritor),
        devolve - consome);

    return this;
  }

  /** Chama um método de instância. */
  public Codigo chamarVirtual(String classe, String nome, String descritor, int consome, int devolve) {
    emitirCurto(
        "invokevirtual " + classe + "." + nome + descritor,
        INVOKEVIRTUAL,
        pool.metodo(classe, nome, descritor),
        devolve - consome);

    return this;
  }

  /** Lê um campo estático, como o {@code System.out}. */
  public Codigo campoEstatico(String classe, String nome, String descritor, int empilha) {
    emitirCurto("getstatic " + classe + "." + nome, GETSTATIC, pool.campo(classe, nome, descritor), empilha);

    return this;
  }

  public Codigo retornarDouble() {
    return emitirSimples("dreturn", DRETURN, -2);
  }

  public Codigo retornar() {
    return emitirSimples("return", RETURN, 0);
  }

  /** A profundidade atual, para conferir num teste que a conta fecha. */
  public int pilhaAtual() {
    return pilhaAtual;
  }
}
