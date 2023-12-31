package Controllers;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Scanner;
import java.util.Vector;
import java.util.concurrent.locks.Lock;

import org.jgroups.Address;
import org.jgroups.JChannel;
import org.jgroups.Receiver;
import org.jgroups.View;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.ResponseMode;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.blocks.locking.LockService;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;

import Models.State;
import Models.Transferencia;
import Models.User;

public class ClusterController implements Receiver {

    private JChannel channel;
    private RpcDispatcher dispatcher;
    private LockService mutex;

    private RMIServerController rmiServer;

    static final int TAMANHO_MINIMO_CLUSTER = 3;
    private boolean eraCoordenador = false;
    private String meuIP = null;

    private Vector<String> usersLogados = new Vector<String>();

    private static int timeout = 2000;

    public ClusterController(JChannel channel, String ipRMI) {
        this.channel = channel;
        this.meuIP = ipRMI;
        this.conectarNoCanal();
        if (this.souCoordenador()) {
            eraCoordenador = true;
        }
    }

    public void bancoServer() throws SocketException {

        while (this.channel.getView().size() < TAMANHO_MINIMO_CLUSTER) {
            Util.sleep(1000);
        }

        if (souCoordenador()) {
            rmiServer = new RMIServerController(this.meuIP);
            rmiServer.start();
        }

        while (true) {
        }
    }

    // -- Métodos de Gerenciamento do Grupo
    public void viewAccepted(View view) {
        System.out.println("Composição mudou.");
        if (souCoordenador()) {
            if (!this.eraCoordenador) {
                eraCoordenador = true;
                System.out.println("Virei o novo coordenador");
                rmiServer = new RMIServerController(this.meuIP);
                rmiServer.start();
            }
        } else { // geral vai pedir o novo estado pro coordenador
            eraCoordenador = false;
        }
        // this.obterEstado();
    }

    public void getState(OutputStream output) {

        try {
            // escreve na saída do pedinte do estado
            Util.objectToStream(new State(this), new DataOutputStream(output));
            System.out.println("Estado enviado.");
        } catch (FileNotFoundException e) {
            System.out.println("Arquivo(s) não encontrado(s) ao transferir estado: " + e.getMessage());
            this.channel.disconnect();
            this.conectarNoCanal();
        } catch (IOException e) {
            System.out.println("Erro ao enviar estado: " + e.getMessage());
        }

    }

    public void setState(InputStream input) {

        try {
            State state = (State) Util.objectFromStream(new DataInputStream(input));

            BufferedOutputStream bfos = new BufferedOutputStream(new FileOutputStream(new File("users.json")));

            bfos.write(state.getUsers());
            bfos.flush();
            bfos.close();

            bfos = new BufferedOutputStream(new FileOutputStream(new File("transferencias.json")));
            bfos.write(state.getTransferencias());
            bfos.flush();
            bfos.close();

            Files.write(Paths.get("versao.txt"), String.valueOf(state.getVersao()).getBytes());

            this.usersLogados = state.getUsersLogados();
            System.out.println("Leu o estado do coordenador: " + this.channel.view().getCoord());
        } catch (IOException e) {
            System.out.println("Erro ao ler estado: " + e.getMessage());
            this.obterEstado();
        } catch (ClassNotFoundException e) {
            System.out.println("Não foi possivel obter a classe do Estado: " + e.getMessage());
            this.obterEstado();
        }

    }
    // ----------------------------------------------------------------------------

    // Métodos da Aplicação -----------
    public User criarConta(String usuario, String senha) {
        System.out.println("---------------------------------------");
        System.out.println(this.channel.getAddress() + " criando conta");
        System.out.println("---------------------------------------");
        User retorno = AuthController.criarConta(usuario, senha);
        if (retorno.getErro() == null) {
            // adiciona como logado
            this.usersLogados.add(retorno.getToken());
        }
        return retorno;
    }

    public User fazerLogin(String usuario, String senha) {
        System.out.println("---------------------------------------");
        System.out.println(this.channel.getAddress() + " fazendo login");
        System.out.println("---------------------------------------");
        User retorno = AuthController.fazerLogin(usuario, senha);
        if (retorno.getErro() == null) {
            if (this.usersLogados.contains(retorno.getToken())) {
                retorno.setErro("Usuário já está logado");
            } else {// nao ta logado, adiciona-lo na lista de loagdos
                this.usersLogados.add(retorno.getToken());
            }
        }
        return retorno;
    }

    public User consultarConta(User conta) {
        if (conta.getUserDB(true) == null) {
            conta = null;
        }
        return conta;
    }

    public User verSaldo(User user) {
        System.out.println("---------------------------------------");
        System.out.println(this.channel.getAddress() + " retornando saldo");
        Lock trava = this.mutex.getLock(String.format("%d", user.getConta()));
        try {
            trava.lock();
            user = ContaController.verSaldo(user);
        } catch (Exception e) {
            user.setErro(e.getMessage());
        } finally {
            trava.unlock();
        }
        System.out.println("---------------------------------------");
        return user;
    }

    public void rollback(State estado) {

        try {
            System.out.println("Rollback...");
            BufferedOutputStream bfos = new BufferedOutputStream(new FileOutputStream(new File("users.json")));
            bfos.write(estado.getUsers());
            bfos.flush();
            bfos.close();

            bfos = new BufferedOutputStream(new FileOutputStream(new File("transferencias.json")));
            bfos.write(estado.getTransferencias());
            bfos.flush();
            bfos.close();
        } catch (Exception e) {// se der qualquer erro na hora de transferir o estado, some do canal.
            this.channel.disconnect();
            this.conectarNoCanal();
        }
    }

    public User transferirDinheiro(Transferencia transferencia) {

        System.out.println("---------------------------------------");
        System.out.println(this.channel.getAddress() + " transferindo dinheiro");
        Util.sleep(5000);

        User origem = transferencia.getUserOrigem();
        User destino = transferencia.getUserDestino();

        System.out.println(String.format("Mutex [%d-%d]", origem.getConta(), destino.getConta()));

        Lock travaC1 = this.mutex.getLock(String.format("%d", origem.getConta()));
        Lock travaC2 = this.mutex.getLock(String.format("%d", destino.getConta()));
        try {
            travaC1.lock();
            travaC2.lock();
            origem = ContaController.transferirDinheiro(transferencia);
        } catch (Exception e) {
            System.out.println("Erro na transferência: " + e.getMessage());
            origem.setErro(e.getMessage());
        } finally {
            travaC1.unlock();
            travaC2.unlock();
        }
        System.out.println("---------------------------------------");
        return origem;
    }

    public ArrayList<Transferencia> obterExtrato(User user) {
        System.out.println("---------------------------------------");
        System.out.println(this.channel.getAddress() + " extrato da conta");
        System.out.println("---------------------------------------");
        return ContaController.obterExtrato(user);
    }

    public Double obterMontante() {
        System.out.println("---------------------------------------");
        System.out.println(this.channel.getAddress() + " obtendo montante");
        System.out.println("---------------------------------------");
        return ContaController.obterMontante();
    }

    public int consultarVersao() {
        return State.consultarVersao();
    }

    // Destruir instância do Login.
    public boolean logout(User user) {
        String token = user.getToken();
        if (usersLogados.contains(token)) {
            usersLogados.remove(token);
            System.out.println("---------------------------------------");
            System.out.println("Logout realizado com sucesso.");
            System.out.println("---------------------------------------");
            return true;
        } else {
            System.out.println("---------------------------------------");
            System.out.println("Não existe usuário logado com o token especificado.");
            System.out.println("---------------------------------------");
            return false;
        }
    }

    // ----------------------------------------------------
    // Getters e Métodos Utilitarios --------
    public RpcDispatcher getDispatcher() {
        return this.dispatcher;
    }

    public JChannel getChannel() {
        return channel;
    }

    public Address getRandomMember() {
        return this.channel.getView().getMembers().get(
                (new Random()).nextInt(this.channel.getView().getMembers().size()));
    }

    private boolean souCoordenador() {
        return (this.channel.getAddress()
                .equals(
                        this.channel.getView().getMembers().get(0)));
    }

    public Vector<String> getUsersLogados() {
        return usersLogados;
    }

    public void setUsersLogados(String token) {
        usersLogados.add(token);
    }

    private void conectarNoCanal() {
        boolean conectou = false;
        int tentativas = 0;
        while (true) {
            try {
                this.channel = channel.connect("testeapresentacao");
                this.dispatcher = new RpcDispatcher(this.channel, this);
                this.dispatcher.setReceiver(this);
                this.mutex = new LockService(this.channel);
                conectou = true;
                System.out.println("Conectado!");
                this.obterEstado();
            } catch (Exception e) {
                // dá uma relaxa por 2 segundos e tenta denovo.
                if (tentativas > 3) {
                    System.out.println("Não foi possível conectar ao canal!" + e.getMessage());
                    System.exit(1);
                }
                System.out.println("Erro ao tentar conectar no canal: " + e.getMessage());
                System.out.println("Tentando novamente...");
                tentativas++;
                Util.sleep(2000);
            }
            if (conectou) {
                break;
            }
        }
    }

    public void desconectar() {
        System.out.println("Desconectando do canal e ressincronizando...");
        this.channel.disconnect();
        this.conectarNoCanal();
    }

    private void obterEstado() {
        boolean obteuEstado = false;
        int tentativas = 0;
        System.out.println("Obtendo estado");
        while (true) {
            try {
                // vendo quem tem o estado mais recente
                RspList<Integer> rsp = this.dispatcher.callRemoteMethods(null, "consultarVersao", null, null,
                        new RequestOptions(ResponseMode.GET_ALL, timeout));
                int maiorVersao = 0;
                Address maiorAddr = null;
                for (Entry<Address, Rsp<Integer>> versao : rsp.entrySet()) {
                    if (versao.getValue().wasReceived()) {
                        if (versao.getValue().getValue() > maiorVersao) {
                            maiorVersao = versao.getValue().getValue();
                            maiorAddr = versao.getKey();
                        }
                    }
                }
                System.out.println("Quem tem a versão mais recente: " + maiorAddr + " Versão " + maiorVersao);
                if (!maiorAddr.equals(this.getChannel().getAddress())) { // não preciso pedir o estado pra mim mesmo
                    this.channel.getState(maiorAddr, 10000);
                }
                obteuEstado = true;
            } catch (Exception e) {
                // dá uma relaxa por 2 segundos e tenta denovo.
                if (tentativas > 3) {
                    System.out.println("Não foi possível obter o estado!" + e.getMessage());
                    // tenta reconectar ao canal pra pegar o estado
                    this.channel.disconnect();
                    this.conectarNoCanal();
                    return;
                }
                System.out.println("Erro ao tentar obter o estado: " + e.getMessage());
                System.out.println("Tentando novamente...");
                tentativas++;
                Util.sleep(2000);
            }
            if (obteuEstado) {
                break;
            }
        }
    }

    public static String obterIP() throws SocketException {
        ArrayList<String> ips = new ArrayList<String>();
        int i = 0;
        System.out.println("---- Seleção de Interface de Rede ----");
        for (Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces(); ifaces
                .hasMoreElements();) {
            NetworkInterface iface = ifaces.nextElement();
            for (Enumeration<InetAddress> addresses = iface.getInetAddresses(); addresses.hasMoreElements();) {
                InetAddress address = addresses.nextElement();
                if (address instanceof Inet6Address) // nao quero ipv6
                    continue;
                ips.add(address.getHostAddress());
                System.out.println(String.format("[%d] %s - %s", i, iface.getName(), address.getHostAddress()));
                i++;
            }
        }

        System.out.println("Escolha a interface de rede adequada: ");
        int op = new Scanner(System.in).nextInt();
        System.out.println("---------------------------------------");
        return ips.get(op);
    }

    // --------------------------------------------------------------------------------

}