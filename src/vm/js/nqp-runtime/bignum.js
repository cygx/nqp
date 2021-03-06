var reprs = require('./reprs.js');

var bignum = require('bignum');

var op = {};
exports.op = op;

op.box_i = function(i, type) {
  var repr = type._STable.REPR;
  var obj = repr.allocate(type._STable);
  obj.$$set_int(i);
  return obj;
};

op.unbox_i = function(obj) {
  return obj.$$get_int();
};

function intish_bool(b) {
  return b ? 1 : 0;
}

function makeBI(type, num) {
  var instance = type._STable.REPR.allocate(type._STable);
  instance.$$set_bignum(num);
  return instance;
}

function getBI(obj) {
  return obj.$$get_bignum();
}

op.fromstr_I = function(str, type) {
  return makeBI(type, bignum(str));
};

op.tostr_I = function(n) {
  return getBI(n).toString();
};

op.base_I = function(n, base) {
  return getBI(n).toString(base).toUpperCase().replace(/^-0+/, '-');
};

op.iseq_I = function(a, b) {
  return intish_bool(getBI(a).eq(getBI(b)));
};

op.mul_I = function(a, b, type) {
  return makeBI(type, getBI(a).mul(getBI(b)));
};

op.add_I = function(a, b, type) {
  return makeBI(type, getBI(a).add(getBI(b)));
};

op.sub_I = function(a, b, type) {
  return makeBI(type, getBI(a).sub(getBI(b)));
};

op.sub_I = function(a, b, type) {
  return makeBI(type, getBI(a).sub(getBI(b)));
};

op.div_I = function(a, b, type) {
  return makeBI(type, getBI(a).div(getBI(b)));
};

op.pow_I = function(a, b, type) {
  return makeBI(type, getBI(a).pow(getBI(b)));
};

op.expmod_I = function(a, b, c, type) {
  return makeBI(type, getBI(a).powm(getBI(b), getBI(c)));
};

op.div_In = function(a, b) {
  var digits = 1e+20;
  return getBI(a).mul(bignum(digits)).div(getBI(b)).toNumber() / digits;
};

op.rand_I = function(max, type) {
  return makeBI(type, getBI(max).rand());
};

op.isle_I = function(a, b) {
  return intish_bool(getBI(a).le(getBI(b)));
};

op.islt_I = function(a, b) {
  return intish_bool(getBI(a).le(getBI(b)));
};

op.isprime_I = function(n) {
  return intish_bool(getBI(n).probPrime(50));
};

op.bitshiftl_I = function(a, b, type) {
  return makeBI(type, getBI(a).shiftLeft(b));
};

op.bitshiftr_I = function(a, b, type) {
  return makeBI(type, getBI(a).shiftRight(b));
};

op.bitshiftr_I = function(a, b, type) {
  return makeBI(type, getBI(a).shiftRight(b));
};

op.bitand_I = function(a, b, type) {
  return makeBI(type, getBI(a).and(getBI(b)));
};

op.bitor_I = function(a, b, type) {
  return makeBI(type, getBI(a).or(getBI(b)));
};

op.bitxor_I = function(a, b, type) {
  return makeBI(type, getBI(a).xor(getBI(b)));
};

op.bitneg_I = function(a, type) {
  return makeBI(type, getBI(a).neg().sub(1));
};

op.lcm_I = function(n, m, type) {
  var a = getBI(n);
  var b = getBI(m);
  return makeBI(type, (a.abs().div(a.gcd(b)).mul(b.abs())));
};

op.gcd_I = function(a, b, type) {
  return makeBI(type, getBI(a).gcd(getBI(b)));
};

op.tonum_I = function(n) {
  return getBI(n).toNumber();
};

op.fromnum_I = function(num, type) {
  // node-bignum bug workaround, when a negative number is too big it gets turned into 0
  if (num < 0) {
    return makeBI(type, bignum(-num).neg());
  } else {
    return makeBI(type, bignum(num));
  }
};

op.bool_I = function(n) {
  return intish_bool(getBI(n).toNumber());
};
