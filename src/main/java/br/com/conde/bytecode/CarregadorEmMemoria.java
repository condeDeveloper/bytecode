package br.com.conde.bytecode;

/**
 * Um carregador que define classes a partir de bytes na memória.
 *
 * <p>{@code defineClass} é protegido em {@link ClassLoader} justamente para
 * que ninguém o chame por acidente: é o ponto onde bytes arbitrários viram
 * código executável. Herdar e expor é o caminho normal para quem gera classe
 * em tempo de execução — e é o que todo framework de proxy faz por baixo.
 */
public final class CarregadorEmMemoria extends ClassLoader {

  public CarregadorEmMemoria(ClassLoader pai) {
    super(pai);
  }

  public CarregadorEmMemoria() {
    this(CarregadorEmMemoria.class.getClassLoader());
  }

  /**
   * Define a classe.
   *
   * <p>Aqui roda só a <b>conferência de formato</b>: assinatura, versão,
   * estrutura do pool, tamanho dos campos. Erro nessa altura vira
   * {@code ClassFormatError} na hora.
   *
   * <p>O <b>verificador de bytecode</b> é outra coisa, e ele é preguiçoso:
   * roda na <i>ligação</i> da classe, no primeiro uso de verdade. Uma classe
   * com a pilha declarada a menos passa por este método sem um pio e só
   * estoura com {@code VerifyError} quando alguém a instancia ou chama um
   * método dela. Há um teste que mostra exatamente essa diferença.
   */
  public Class<?> definir(String nomeJava, byte[] bytes) {
    return defineClass(nomeJava, bytes, 0, bytes.length);
  }
}
