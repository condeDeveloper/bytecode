package br.com.conde.bytecode.classe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * O arquivo {@code .class}.
 *
 * <p>A estrutura inteira cabe aqui, e é surpreendentemente pequena:
 *
 * <pre>
 *   CAFEBABE            4 bytes de assinatura
 *   menor, maior        a versão do formato
 *   pool de constantes
 *   flags, esta classe, superclasse
 *   interfaces
 *   campos
 *   métodos
 *   atributos
 * </pre>
 *
 * <p>O {@code 0xCAFEBABE} está lá desde 1995 e é literalmente um trocadilho:
 * James Gosling contou que o time almoçava num lugar chamado St Michael's
 * Alley, que tocava Grateful Dead, e o apelido do lugar era "Cafe Dead" — daí
 * também o {@code 0xCAFEDEAD} de outro formato da Sun.
 *
 * <p>A versão importa mais do que parece. A partir da <b>50</b> (Java 6), todo
 * método com desvio precisa de um atributo {@code StackMapTable} descrevendo o
 * estado da pilha em cada alvo de salto. Foi o preço de trocar o verificador
 * por inferência, lento, por um que só confere o que a classe declara. Este
 * projeto gera código <b>sem desvio</b> e por isso não precisa dessa tabela —
 * o que é uma limitação declarada, não um descuido.
 */
public final class EscritorDeClasse {

  /** A assinatura que todo arquivo .class carrega. */
  public static final int ASSINATURA = 0xCAFEBABE;

  /** 52 é Java 8. */
  public static final int VERSAO_JAVA_8 = 52;

  public static final int PUBLICA = 0x0001;
  public static final int PRIVADA = 0x0002;
  public static final int ESTATICA = 0x0008;
  public static final int FINAL = 0x0010;
  public static final int SUPER = 0x0020;
  public static final int INTERFACE = 0x0200;
  public static final int ABSTRATA = 0x0400;

  private final PoolDeConstantes pool = new PoolDeConstantes();
  private final String nomeInterno;
  private final String superclasse;
  private final List<String> interfaces = new ArrayList<>();
  private final List<Metodo> metodos = new ArrayList<>();
  private final int flags;
  private final int versao;

  private record Metodo(int flags, String nome, String descritor, Codigo codigo) {}

  public EscritorDeClasse(String nomeJava) {
    this(nomeJava, "java.lang.Object", PUBLICA | SUPER, VERSAO_JAVA_8);
  }

  public EscritorDeClasse(String nomeJava, String superclasseJava, int flags, int versao) {
    this.nomeInterno = PoolDeConstantes.nomeInterno(nomeJava);
    this.superclasse = PoolDeConstantes.nomeInterno(superclasseJava);
    this.flags = flags;
    this.versao = versao;
  }

  public PoolDeConstantes pool() {
    return pool;
  }

  public String nomeInterno() {
    return nomeInterno;
  }

  /** Declara que a classe implementa uma interface. */
  public EscritorDeClasse implementa(String nomeJava) {
    interfaces.add(PoolDeConstantes.nomeInterno(nomeJava));

    return this;
  }

  /** Começa um método e devolve o montador do corpo dele. */
  public Codigo metodo(int flagsDoMetodo, String nome, String descritor) {
    boolean estatico = (flagsDoMetodo & ESTATICA) != 0;

    // As variáveis locais já começam ocupadas: a posição 0 é o `this` (em
    // método de instância) e em seguida vêm os parâmetros. Declarar menos do
    // que isso faz a JVM recusar a classe com "Arguments can't fit into
    // locals" — antes de executar qualquer coisa.
    int ocupadas = (estatico ? 0 : 1) + posicoesDosArgumentos(descritor);
    Codigo codigo = new Codigo(pool, ocupadas);

    metodos.add(new Metodo(flagsDoMetodo, nome, descritor, codigo));

    return codigo;
  }

  /**
   * Quantas posições de variável local os argumentos de um descritor ocupam.
   *
   * <p>Quase todo tipo ocupa uma, mas {@code long} e {@code double} ocupam
   * <b>duas</b>. É a mesma pegadinha do pool de constantes, e ela reaparece em
   * todo lugar onde a JVM conta espaço.
   */
  public static int posicoesDosArgumentos(String descritor) {
    int abre = descritor.indexOf('(');
    int fecha = descritor.indexOf(')');

    if (abre != 0 || fecha < 0) {
      throw new IllegalArgumentException("Descritor de método malformado: " + descritor);
    }

    int total = 0;
    int i = 1;

    while (i < fecha) {
      char tipo = descritor.charAt(i);

      if (tipo == '[') {
        // Um vetor é uma referência, não importa quantas dimensões tenha.
        while (descritor.charAt(i) == '[') {
          i += 1;
        }

        if (descritor.charAt(i) == 'L') {
          i = descritor.indexOf(';', i);
        }

        total += 1;
        i += 1;
        continue;
      }

      if (tipo == 'L') {
        i = descritor.indexOf(';', i) + 1;
        total += 1;
        continue;
      }

      total += tipo == 'J' || tipo == 'D' ? 2 : 1;
      i += 1;
    }

    return total;
  }

  /**
   * Acrescenta o construtor vazio.
   *
   * <p>Sem ele a classe carrega, mas {@code newInstance} falha: o compilador
   * Java põe um construtor padrão automaticamente, e quem gera bytecode na mão
   * precisa lembrar de fazer o mesmo.
   */
  public EscritorDeClasse construtorVazio() {
    Codigo codigo = metodo(PUBLICA, "<init>", "()V");

    codigo.carregarReferencia(0);
    codigo.chamarEspecial("java/lang/Object", "<init>", "()V", 1, 0);
    codigo.retornar();

    return this;
  }

  /** Monta os bytes do arquivo. */
  public byte[] bytes() {
    // O pool é preenchido enquanto os métodos são montados, então ele só pode
    // ser escrito agora — e por isso a classe inteira é montada em memória
    // antes de sair qualquer byte.
    int indiceDestaClasse = pool.classe(nomeInterno);
    int indiceDaSuper = pool.classe(superclasse);
    int[] indicesDeInterface = interfaces.stream().mapToInt(pool::classe).toArray();
    int indiceDeCode = pool.utf8("Code");

    List<byte[]> corposDosMetodos = new ArrayList<>();

    for (Metodo metodo : metodos) {
      corposDosMetodos.add(corpoDoMetodo(metodo, indiceDeCode));
    }

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    try (DataOutputStream saida = new DataOutputStream(bytes)) {
      saida.writeInt(ASSINATURA);
      saida.writeShort(0);
      saida.writeShort(versao);

      pool.escrever(saida);

      saida.writeShort(flags);
      saida.writeShort(indiceDestaClasse);
      saida.writeShort(indiceDaSuper);

      saida.writeShort(indicesDeInterface.length);

      for (int indice : indicesDeInterface) {
        saida.writeShort(indice);
      }

      saida.writeShort(0); // nenhum campo

      saida.writeShort(corposDosMetodos.size());

      for (byte[] corpo : corposDosMetodos) {
        saida.write(corpo);
      }

      saida.writeShort(0); // nenhum atributo de classe
    } catch (IOException erro) {
      throw new UncheckedIOException("Não consegui montar a classe", erro);
    }

    return bytes.toByteArray();
  }

  private byte[] corpoDoMetodo(Metodo metodo, int indiceDeCode) {
    byte[] codigo = metodo.codigo().bytes();

    if (codigo.length == 0) {
      throw new IllegalStateException("O método " + metodo.nome() + " ficou sem corpo");
    }

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    try (DataOutputStream saida = new DataOutputStream(bytes)) {
      saida.writeShort(metodo.flags());
      saida.writeShort(pool.utf8(metodo.nome()));
      saida.writeShort(pool.utf8(metodo.descritor()));
      saida.writeShort(1); // um atributo: o Code

      saida.writeShort(indiceDeCode);
      // O tamanho do atributo: pilha, locais, tamanho do código, o código,
      // zero tratadores de exceção e zero atributos internos.
      saida.writeInt(2 + 2 + 4 + codigo.length + 2 + 2);
      saida.writeShort(metodo.codigo().pilhaMaxima());
      saida.writeShort(metodo.codigo().variaveis());
      saida.writeInt(codigo.length);
      saida.write(codigo);
      saida.writeShort(0); // sem tratadores de exceção
      saida.writeShort(0); // sem LineNumberTable nem StackMapTable
    } catch (IOException erro) {
      throw new UncheckedIOException("Não consegui montar o método " + metodo.nome(), erro);
    }

    return bytes.toByteArray();
  }

  /** A montagem de todos os métodos, em texto, para inspeção. */
  public String montagem() {
    StringBuilder texto = new StringBuilder();

    for (Metodo metodo : metodos) {
      texto.append(metodo.nome()).append(metodo.descritor()).append('\n');

      for (String linha : metodo.codigo().montagem()) {
        texto.append("  ").append(linha).append('\n');
      }
    }

    return texto.toString();
  }
}
