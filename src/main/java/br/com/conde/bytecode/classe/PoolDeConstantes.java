package br.com.conde.bytecode.classe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O pool de constantes.
 *
 * <p>É a tabela onde um arquivo {@code .class} guarda tudo que não é código:
 * nomes de classe, nomes de método, descritores, literais. O bytecode não
 * carrega texto nenhum — ele carrega <b>índices</b> para esta tabela. Ler o
 * bytecode sem ler o pool não diz nada.
 *
 * <p>Três estranhezas do formato, todas herdadas de 1995 e todas ainda valendo:
 *
 * <ol>
 *   <li><b>O índice começa em 1</b>, não em 0. O índice 0 significa "nenhum",
 *       e é usado, por exemplo, na superclasse de {@code java.lang.Object}.
 *   <li><b>{@code long} e {@code double} ocupam duas posições.</b> A segunda
 *       fica vazia e nunca é referenciada. A especificação chama isso de
 *       "decisão infeliz" — nas palavras dela mesma.
 *   <li><b>O texto não é UTF-8.</b> É "UTF-8 modificado": o caractere nulo vira
 *       dois bytes, e o que está fora do plano básico vira um par substituto
 *       codificado separadamente. É o que {@link DataOutputStream#writeUTF}
 *       faz, e usar UTF-8 de verdade gera uma classe que a JVM recusa.
 * </ol>
 */
public final class PoolDeConstantes {

  /** Etiquetas das entradas que este projeto usa. */
  public static final int UTF8 = 1;
  public static final int INTEGER = 3;
  public static final int FLOAT = 4;
  public static final int LONG = 5;
  public static final int DOUBLE = 6;
  public static final int CLASSE = 7;
  public static final int TEXTO = 8;
  public static final int CAMPO = 9;
  public static final int METODO = 10;
  public static final int METODO_DE_INTERFACE = 11;
  public static final int NOME_E_TIPO = 12;

  private final List<Entrada> entradas = new ArrayList<>();
  private final Map<String, Integer> cache = new HashMap<>();

  /** Uma entrada já serializada, com a etiqueta na frente. */
  private record Entrada(int etiqueta, byte[] corpo, boolean ocupaDuas) {}

  /** Quantas posições o pool declara. Sempre uma a mais que o total real. */
  public int tamanho() {
    return entradas.size() + 1;
  }

  /**
   * Acrescenta uma entrada, reaproveitando a que já existe.
   *
   * <p>A deduplicação não é otimização: a especificação exige que duas
   * entradas UTF-8 com o mesmo texto não apareçam duas vezes, e um verificador
   * rigoroso recusa a classe.
   */
  private int acrescentar(String chave, int etiqueta, byte[] corpo, boolean ocupaDuas) {
    Integer existente = cache.get(chave);

    if (existente != null) {
      return existente;
    }

    int indice = entradas.size() + 1;

    entradas.add(new Entrada(etiqueta, corpo, ocupaDuas));

    // A posição fantasma do long e do double: ela existe na contagem e nunca
    // é referenciada por nada.
    if (ocupaDuas) {
      entradas.add(null);
    }

    cache.put(chave, indice);

    return indice;
  }

  /** Um texto em UTF-8 modificado. */
  public int utf8(String texto) {
    return acrescentar("utf8:" + texto, UTF8, comEscrita(saida -> saida.writeUTF(texto)), false);
  }

  /** Um nome de classe, no formato interno com barras: {@code java/lang/System}. */
  public int classe(String nomeInterno) {
    int nome = utf8(nomeInterno);

    return acrescentar("classe:" + nomeInterno, CLASSE, comEscrita(saida -> saida.writeShort(nome)), false);
  }

  /** Um literal de texto — o que um {@code "oi"} no código vira. */
  public int texto(String valor) {
    int conteudo = utf8(valor);

    return acrescentar("texto:" + valor, TEXTO, comEscrita(saida -> saida.writeShort(conteudo)), false);
  }

  /** Um literal inteiro. */
  public int inteiro(int valor) {
    return acrescentar("int:" + valor, INTEGER, comEscrita(saida -> saida.writeInt(valor)), false);
  }

  /** Um literal {@code long}, que ocupa duas posições. */
  public int longo(long valor) {
    return acrescentar("long:" + valor, LONG, comEscrita(saida -> saida.writeLong(valor)), true);
  }

  /** Um literal {@code double}, que também ocupa duas. */
  public int duplo(double valor) {
    return acrescentar("double:" + valor, DOUBLE, comEscrita(saida -> saida.writeDouble(valor)), true);
  }

  /** O par nome + descritor, que é como um membro é identificado. */
  public int nomeETipo(String nome, String descritor) {
    int indiceDoNome = utf8(nome);
    int indiceDoTipo = utf8(descritor);

    return acrescentar(
        "nt:" + nome + ":" + descritor,
        NOME_E_TIPO,
        comEscrita(
            saida -> {
              saida.writeShort(indiceDoNome);
              saida.writeShort(indiceDoTipo);
            }),
        false);
  }

  /** Uma referência a método: classe + nome + descritor. */
  public int metodo(String classe, String nome, String descritor) {
    return referencia(METODO, classe, nome, descritor);
  }

  /** Uma referência a método de interface — etiqueta diferente, formato igual. */
  public int metodoDeInterface(String classe, String nome, String descritor) {
    return referencia(METODO_DE_INTERFACE, classe, nome, descritor);
  }

  /** Uma referência a campo. */
  public int campo(String classe, String nome, String descritor) {
    return referencia(CAMPO, classe, nome, descritor);
  }

  private int referencia(int etiqueta, String classe, String nome, String descritor) {
    int indiceDaClasse = classe(classe);
    int indiceDoNomeETipo = nomeETipo(nome, descritor);

    return acrescentar(
        etiqueta + ":" + classe + ":" + nome + ":" + descritor,
        etiqueta,
        comEscrita(
            saida -> {
              saida.writeShort(indiceDaClasse);
              saida.writeShort(indiceDoNomeETipo);
            }),
        false);
  }

  /** Escreve o pool inteiro. */
  public void escrever(DataOutputStream saida) throws IOException {
    saida.writeShort(tamanho());

    for (Entrada entrada : entradas) {
      // A posição fantasma não é escrita; ela só ocupa lugar na contagem.
      if (entrada == null) {
        continue;
      }

      saida.writeByte(entrada.etiqueta());
      saida.write(entrada.corpo());
    }
  }

  /** O que uma entrada faz quando escreve a si mesma. */
  @FunctionalInterface
  private interface Escrita {
    void escrever(DataOutputStream saida) throws IOException;
  }

  private static byte[] comEscrita(Escrita escrita) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    try (DataOutputStream saida = new DataOutputStream(bytes)) {
      escrita.escrever(saida);
    } catch (IOException erro) {
      throw new UncheckedIOException("Não consegui montar a entrada do pool", erro);
    }

    return bytes.toByteArray();
  }

  /** O nome interno de uma classe: pontos viram barras. */
  public static String nomeInterno(String nomeJava) {
    return nomeJava.replace('.', '/');
  }

  /** Só para inspeção em teste: o texto de uma entrada UTF-8. */
  public String textoEm(int indice) {
    Entrada entrada = entradas.get(indice - 1);

    if (entrada == null || entrada.etiqueta() != UTF8) {
      throw new IllegalArgumentException("A posição " + indice + " não é um UTF-8");
    }

    byte[] corpo = entrada.corpo();
    int comprimento = ((corpo[0] & 0xFF) << 8) | (corpo[1] & 0xFF);

    return new String(corpo, 2, comprimento, StandardCharsets.UTF_8);
  }
}
