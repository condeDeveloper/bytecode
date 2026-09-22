package br.com.conde.bytecode.compilador;

import java.util.List;

/**
 * A árvore da expressão.
 *
 * <p>Selada porque o compilador trata todos os casos num {@code switch} de
 * padrão: acrescentar um nó novo sem tratar dele passa a ser erro de
 * compilação, e não um caso esquecido em tempo de execução.
 */
public sealed interface No {

  record Constante(double valor) implements No {}

  record Variavel(String nome, int indice) implements No {}

  record Negacao(No dentro) implements No {}

  record Binario(char operador, No esquerda, No direita) implements No {}

  record Chamada(String nome, List<No> argumentos) implements No {}
}
