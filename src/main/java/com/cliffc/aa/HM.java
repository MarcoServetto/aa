package com.cliffc.aa;

import com.cliffc.aa.type.*;
import com.cliffc.aa.util.Ary;
import com.cliffc.aa.util.SB;
import com.cliffc.aa.util.VBitSet;
import org.jetbrains.annotations.NotNull;

import java.util.*;

// Hindley-Milner typing.  Complete stand-alone, for research.  MEETs base
// types, instead of declaring type error.  Requires SSA renumbering; uses a
// global Env instead locally tracking.
//
// Testing in this version changing out the AST tree-walk for a worklist based
// approach, where unification happens in any order.  In particular, it means
// that (unlike a tree-walk), function types will get used before the function
// is typed.  This means that a "fresh" copy of a function type to be used to
// unify against will not have all the contraints at first unification.


public class HM {
  static final HashMap<String,HMType> ENV = new HashMap<>();

  public static HMType hm( Syntax prog) {
    Object dummy = TypeStruct.DISPLAY;

    // Simple types
    HMVar bool  = new HMVar(TypeInt.BOOL);
    HMVar int64 = new HMVar(TypeInt.INT64);
    HMVar flt64 = new HMVar(TypeFlt.FLT64);
    HMVar strp  = new HMVar(TypeMemPtr.STRPTR);

    // Primitives
    HMVar var1 = new HMVar();
    HMVar var2 = new HMVar();
    ENV.put("pair",Oper.fun(var1, Oper.fun(var2, new Oper("pair",var1,var2))));

    HMVar var3 = new HMVar();
    ENV.put("if/else",Oper.fun(bool,Oper.fun(var3,Oper.fun(var3,var3))));

    ENV.put("dec",Oper.fun(int64,int64));
    ENV.put("*",Oper.fun(int64,Oper.fun(int64,int64)));
    ENV.put("==0",Oper.fun(int64,bool));

    // Print a string; int->str
    ENV.put("str",Oper.fun(int64,strp));
    // Factor
    ENV.put("factor",Oper.fun(flt64,new Oper("pair",flt64,flt64)));


    // Prep for SSA: pre-gather all the (unique) ids.  Store a linked-list of
    // non-generative IDs (those mid-definition in Lambda & Let, or in the old
    // "nongen" HashSet), for use by Ident.hm.
    prog.get_ids(null);

    // Worklist:
    final Ary<Syntax> ary = new Ary<>(Syntax.class); // For picking random element
    final HashSet<Syntax> work = new HashSet<>();    // For preventing dups
    prog.add_work(ary,work);
    while( ary.len()>0 ) {
      Syntax s = ary.pop();
      work.remove(s);
      HMType old = s._hm;
      HMType nnn = s.hm(); if( nnn!=null ) nnn = nnn.find();
      if( nnn==null || !nnn.eq(old,new HashMap<>()) ) {
        s._hm = nnn;
        if( s._par!=null ) s._par.add_work(ary,work);
        s.add_kids(ary,work);
      } else if( nnn!=old )
        old.find().union(nnn);
    }
    return prog._hm;
  }
  static void reset() { ENV.clear(); HMVar.reset(); }

  static class VStack implements Iterable<HMVar> {
    final VStack _par;
    final HMVar _nongen;
    VStack( VStack par, HMVar nongen ) { _par=par; _nongen=nongen; }
    /** @return an iterator */
    @NotNull
    @Override public Iterator<HMVar> iterator() { return new Iter(); }
    private class Iter implements Iterator<HMVar> {
      private VStack _vstk;
      Iter() { _vstk=VStack.this; }
      @Override public boolean hasNext() { return _vstk!=null; }
      @Override public HMVar next() { HMVar v = _vstk._nongen; _vstk = _vstk._par;  return v; }
    }

  }

  public static abstract class Syntax {
    abstract HMType hm();
    abstract void get_ids(VStack vstk);
    Syntax _par;
    HMType _hm;
    final void add_work(Ary<Syntax> ary,HashSet<Syntax> work) {
      if( !work.contains(this) )
        work.add(ary.push(this));
    }
    void add_kids(Ary<Syntax> ary,HashSet<Syntax> work) {}
  }
  public static class Con extends Syntax {
    final Type _t;
    Con(Type t) { _t=t; }
    @Override public String toString() { return _t.toString(); }
    @Override HMType hm() { return new HMVar(_t); }
    @Override void get_ids(VStack vstk) {}
  }
  public static class Ident extends Syntax {
    final String _name;
    VStack _vstk;
    Ident(String name) { _name=name; }
    @Override public String toString() { return _name; }
    @Override HMType hm() {
      HMType t = ENV.get(_name);
      if( t==null )
        throw new RuntimeException("Parse error, "+_name+" is undefined");
      HMType f = t.fresh(_vstk);
      return f;
    }
    @Override void get_ids(VStack vstk) { _vstk=vstk; }
  }
  public static class Lambda extends Syntax {
    final String _arg0;
    final Syntax _body;
    Lambda(String arg0, Syntax body) { _arg0=arg0; _body=body; _body._par=this; }
    @Override public String toString() { return "{ "+_arg0+" -> "+_body+" }"; }
    @Override HMType hm() {
      HMVar  tnew = (HMVar) ENV.get(_arg0);
      HMType trez = _body._hm;
      if( trez==null ) return null;
      return Oper.fun(tnew,trez);
    }
    @Override void get_ids(VStack vstk) {
      HMVar var = new HMVar();
      ENV.put(_arg0, var);
      _body.get_ids(new VStack(vstk,var));
    }
    void add_kids(Ary<Syntax> ary,HashSet<Syntax> work) { _body.add_work(ary,work); }
  }
  public static class Let extends Syntax {
    final String _arg0;
    final Syntax _body, _use;
    Let(String arg0, Syntax body, Syntax use) { _arg0=arg0; _body=body; _use=use; _body._par=this; _use._par=this; }
    @Override public String toString() { return "let "+_arg0+" = "+_body+" in "+_use+" }"; }
    @Override HMType hm() {
      HMType tbody = _body._hm;
      HMType trez  = _use ._hm;
      if( tbody==null || trez==null ) return null;
      HMVar tnew = (HMVar) ENV.get(_arg0);
      tnew.union(tbody.find());
      return trez.find();
    }
    @Override void get_ids(VStack vstk) {
      HMVar var = new HMVar();
      ENV.put(_arg0, var);
      _body.get_ids(new VStack(vstk,var));
      _use .get_ids(vstk);
    }
    void add_kids(Ary<Syntax> ary,HashSet<Syntax> work) { _body.add_work(ary,work); _use.add_work(ary,work); }
  }
  public static class Apply extends Syntax {
    final Syntax _fun, _arg;
    Apply(Syntax fun, Syntax arg) { _fun=fun; _arg=arg; _fun._par=this; _arg._par=this; }
    @Override public String toString() { return "("+_fun+" "+_arg+")"; }
    @Override HMType hm() {
      HMType tfun = _fun._hm;
      HMType targ = _arg._hm;
      if( tfun==null || targ==null ) return null;
      HMType trez = new HMVar();
      HMType nfun = Oper.fun(targ.find(),trez);
      nfun.union(tfun);
      return trez;
    }
    @Override void get_ids(VStack vstk) { _fun.get_ids(vstk); _arg.get_ids(vstk); }
    void add_kids(Ary<Syntax> ary,HashSet<Syntax> work) { _fun.add_work(ary,work); _arg.add_work(ary,work); }
  }



  public static abstract class HMType {
    HMType _u;                  // U-F; always null for Oper
    abstract HMType union(HMType t);
    abstract HMType find();
    public String str() { return find()._str(); }
    abstract String _str();
    boolean is_top() { return _u==null; }
    abstract boolean eq( HMType v, HashMap<HMVar,HMVar> map );

    HMType fresh(VStack vstk) {
      HashMap<HMType,HMType> vars = new HashMap<>();
      return _fresh(vstk,vars);
    }
    HMType _fresh(VStack vstk, HashMap<HMType,HMType> vars) {
      HMType t2 = find();
      if( t2 instanceof HMVar ) {
        return t2.occurs_in(vstk)   //
          ? t2                      // Keep same var
          : vars.computeIfAbsent(t2, e -> new HMVar(((HMVar)t2)._t));
      } else {
        Oper op = (Oper)t2;
        HMType[] args = new HMType[op._args.length];
        for( int i=0; i<args.length; i++ )
          args[i] = op._args[i]._fresh(vstk,vars);
        return new Oper(op._name,args);
      }
    }

    boolean occurs_in(VStack vstk) {
      if( vstk==null ) return false;
      for( HMVar x : vstk ) if( occurs_in_type(x) ) return true;
      return false;
    }
    boolean occurs_in(HMType[] args) {
      for( HMType x : args ) if( occurs_in_type(x) ) return true;
      return false;
    }
    boolean occurs_in_type(HMType v) {
      assert is_top();
      HMType y = v.find();
      if( y==this )
        return true;
      if( y instanceof Oper )
        return occurs_in(((Oper)y)._args);
      return false;
    }
  }

  static class HMVar extends HMType {
    private Type _t;
    private final int _uid;
    private static int CNT;
    HMVar() { this(Type.ANY); }
    HMVar(Type t) { _uid=CNT++; _t=t; }
    static void reset() { CNT=1; }
    public Type type() { assert is_top(); return _t; }
    @Override public String toString() {
      String s = _str();
      if( _u!=null ) s += ">>"+_u;
      return s;
    }
    @Override public String _str() {
      String s = "v"+_uid;
      if( _t!=Type.ANY ) s += ":"+_t.str(new SB(),new VBitSet(),null,false);
      return s;
    }

    @Override HMType find() {
      HMType u = _u;
      if( u==null ) return this; // Top of union tree
      if( u._u==null ) return u; // One-step from top
      // Classic U-F rollup
      while( u._u!=null ) u = u._u; // Find the top
      HMType x = this;              // Collapse all to top
      while( x._u!=u ) { HMType tmp = x._u; x._u=u; x=tmp;}
      return u;
    }
    @Override HMType union(HMType that) {
      if( this==that ) return this; // Do nothing
      if( _u!=null ) return find().union(that);
      if( that instanceof HMVar ) that = that.find();
      if( occurs_in_type(that) )
        throw new RuntimeException("recursive unification");

      if( that instanceof HMVar ) {
        HMVar v2 = (HMVar)that;
        v2._t = _t.meet(v2._t);
      }
      else assert _t==Type.ANY; // Else this var is un-MEETd with any Con
      return _u = that;         // Classic U-F union
    }

    @Override boolean eq( HMType v, HashMap<HMVar,HMVar> map ) {
      if( this==v ) return true;
      if( v==null ) return false;
      HMType v2 = v.find();
      if( !(v2 instanceof HMVar) ) return false;
      assert _u==null && v2._u==null;
      if( _t != ((HMVar)v2)._t) return false;
      HMVar v3 = map.computeIfAbsent(this,k -> (HMVar)v2);
      return v2 == v3;
    }
  }

  static class Oper extends HMType {
    final String _name;
    final HMType[] _args;
    Oper(String name, HMType... args) { _name=name; _args=args; }
    static Oper fun(HMType... args) { return new Oper("->",args); }
    @Override public String toString() {
      if( _name.equals("->") ) return "{ "+_args[0]+" -> "+_args[1]+" }";
      return _name+" "+Arrays.toString(_args);
    }
    @Override public String _str() {
      if( _name.equals("->") )
        return "{ "+_args[0].str()+" -> "+_args[1].str()+" }";
      SB sb = new SB().p(_name).p('(');
      for( HMType t : _args )
        sb.p(t.str()).p(',');
      return sb.unchar().p(')').toString();
    }

    @Override HMType find() { return this; }
    @Override HMType union(HMType that) {
      if( this==that ) return this;
      if( !(that instanceof Oper) ) return that.union(this);
      Oper op2 = (Oper)that;
      if( !_name.equals(op2._name) ||
          _args.length != op2._args.length )
        throw new RuntimeException("Cannot unify "+this+" and "+that);
      for( int i=0; i<_args.length; i++ )
        _args[i].union(op2._args[i]);
      return this;
    }
    @Override boolean eq( HMType v, HashMap<HMVar,HMVar> map ) {
      if( this==v ) return true;
      if( !(v instanceof Oper) ) return false;
      Oper o = (Oper)v;
      if( !_name.equals(o._name) ||
          _args.length!=o._args.length ) return false;
      for( int i=0; i<_args.length; i++ )
        if( !_args[i].find().eq(o._args[i].find(),map) )
          return false;
      return true;
    }
  }
}
