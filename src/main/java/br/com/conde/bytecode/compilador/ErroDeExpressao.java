package br.com.conde.bytecode.compilador;

/** A expressão não pôde ser lida. A posição é a do caractere no texto. */
public final class ErroDeExpressao extends RuntimeException {

  private final int posicao;

  public ErroDeExpressao(String mensagem, int posicao) {
    super(mensagem + " (posição " + posicao + ")");
    this.posicao = posicao;
  }

  public int posicao() {
    return posicao;
  }
}
