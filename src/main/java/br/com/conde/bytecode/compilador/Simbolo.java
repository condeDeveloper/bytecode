package br.com.conde.bytecode.compilador;

/** Um pedaço reconhecido do texto. */
public record Simbolo(Tipo tipo, String texto, int posicao) {

  public enum Tipo {
    NUMERO,
    NOME,
    OPERADOR,
    ABRE,
    FECHA,
    VIRGULA,
    FIM
  }

  /** Como o símbolo aparece numa mensagem de erro. */
  public String descricao() {
    return tipo == Tipo.FIM ? "o fim da expressão" : "\"" + texto + "\"";
  }
}
